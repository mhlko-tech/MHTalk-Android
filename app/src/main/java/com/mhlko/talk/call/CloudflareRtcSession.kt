package com.mhlko.talk.call

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.mhlko.talk.data.RoomCredentials
import com.mhlko.talk.data.ShareQuality
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CloudflareMember(
    val identity: String,
    val speaking: Boolean = false,
    val microphoneEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    val screenShareEnabled: Boolean = false,
)

private data class CloudflareTrackMetadata(
    val sessionId: String,
    val trackName: String,
    val mid: String? = null,
) {
    fun json(location: String = "remote") = JSONObject()
        .put("sessionId", sessionId)
        .put("trackName", trackName)
        .put("location", location)
        .apply { mid?.let { put("mid", it) } }
}

/** Native Android adapter for Cloudflare Realtime's sessions/tracks HTTP API. */
internal class CloudflareRtcSession(
    context: Context,
    private val accessToken: () -> String?,
    private val onMembers: (List<CloudflareMember>) -> Unit,
    private val onPayload: (String, JSONObject) -> Unit,
    private val onConnectionState: (ConnectionState) -> Unit,
    private val onScreenShareStopped: () -> Unit,
) {
    enum class ConnectionState { Connecting, Connected, Reconnecting, Failed }
    enum class TrackSource { Camera, Screen }

    private val appContext = context.applicationContext
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var credentials: RoomCredentials? = null
    private var peer: PeerConnection? = null
    private var sessionId: String? = null
    private var socket: WebSocket? = null
    private var socketOpened = CompletableDeferred<Unit>()
    private var audioSource: AudioSource? = null
    private var microphoneTrack: AudioTrack? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private val members = linkedMapOf<String, CloudflareMember>()
    private val remoteMetadata = mutableMapOf<String, Map<String, CloudflareTrackMetadata>>()
    private val remoteTracks = mutableMapOf<String, MediaStreamTrack>()
    private val pulledMetadata = mutableMapOf<String, CloudflareTrackMetadata>()
    private val localMetadata = mutableMapOf<String, CloudflareTrackMetadata>()
    private val watched = mutableSetOf<String>()
    private var microphoneEnabled = false
    private var cameraEnabled = false
    private var screenEnabled = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    val connected: Boolean
        get() = peer != null && socketOpened.isCompleted

    val identity: String?
        get() = credentials?.identity

    val participants: List<CloudflareMember>
        get() = members.values.toList()

    suspend fun connect(credentials: RoomCredentials, microphoneEnabled: Boolean) {
        disconnect()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        this.credentials = credentials
        this.microphoneEnabled = microphoneEnabled
        onConnectionState(ConnectionState.Connecting)
        val createdSession = post("${baseUrl()}/partytracks/sessions/new", JSONObject())
        sessionId = createdSession.requireString("sessionId")
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder(
                    listOf("stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"),
                ).createIceServer(),
            ),
        ).apply { bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE }
        peer = factory.createPeerConnection(rtcConfig, peerObserver)
            ?: error("Could not create Cloudflare peer connection")
        createMicrophoneTrack(microphoneEnabled)
        createCameraTrack()
        publishLocalTracks(listOfNotNull(microphoneTrack?.let { "audio" to it }, cameraTrack?.let { "camera" to it }))
        openRoomSocket(credentials)
        socketOpened.await()
        publishState()
        onConnectionState(ConnectionState.Connected)
    }

    fun disconnect() {
        socket?.close(1000, "Leaving room")
        socket = null
        scope.cancel()
        members.clear()
        remoteMetadata.clear()
        remoteTracks.values.forEach(MediaStreamTrack::dispose)
        remoteTracks.clear()
        pulledMetadata.clear()
        localMetadata.clear()
        watched.clear()
        runCatching { cameraCapturer?.stopCapture() }
        runCatching { screenCapturer?.stopCapture() }
        cameraCapturer?.dispose()
        screenCapturer?.dispose()
        cameraHelper?.dispose()
        screenHelper?.dispose()
        cameraTrack?.dispose()
        screenTrack?.dispose()
        microphoneTrack?.dispose()
        cameraSource?.dispose()
        screenSource?.dispose()
        audioSource?.dispose()
        peer?.close()
        peer?.dispose()
        credentials = null
        peer = null
        sessionId = null
        socketOpened = CompletableDeferred()
        microphoneTrack = null
        cameraTrack = null
        screenTrack = null
        cameraCapturer = null
        screenCapturer = null
        cameraHelper = null
        screenHelper = null
        cameraSource = null
        screenSource = null
        audioSource = null
        microphoneEnabled = false
        cameraEnabled = false
        screenEnabled = false
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneTrack?.setEnabled(enabled)
        microphoneEnabled = enabled
        publishState()
    }

    fun setCameraEnabled(enabled: Boolean) {
        cameraTrack?.setEnabled(enabled)
        cameraEnabled = enabled
        publishState()
    }

    suspend fun startScreenShare(permissionData: Intent, quality: ShareQuality) {
        if (screenTrack == null) {
            val dimensions = when (quality) {
                ShareQuality.Low -> Triple(640, 360, 15)
                ShareQuality.Medium -> Triple(1280, 720, 20)
                ShareQuality.High -> Triple(1920, 1080, 30)
            }
            val source = factory.createVideoSource(true)
            val capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
                override fun onStop() {
                    screenEnabled = false
                    publishState()
                    onScreenShareStopped()
                }
            })
            val helper = SurfaceTextureHelper.create("CloudflareScreen", egl.eglBaseContext)
            capturer.initialize(helper, appContext, source.capturerObserver)
            capturer.startCapture(dimensions.first, dimensions.second, dimensions.third)
            val track = factory.createVideoTrack("mhtalk-screen-${UUID.randomUUID()}", source)
            screenSource = source
            screenCapturer = capturer
            screenHelper = helper
            screenTrack = track
            publishLocalTracks(listOf("screen" to track))
        }
        screenTrack?.setEnabled(true)
        screenEnabled = true
        publishState()
    }

    fun stopScreenShare() {
        screenTrack?.setEnabled(false)
        screenEnabled = false
        publishState()
    }

    fun switchCamera() = cameraCapturer?.switchCamera(null)

    fun videoTrack(identity: String?, source: TrackSource): VideoTrack? {
        if (identity == null) return if (source == TrackSource.Camera) cameraTrack else screenTrack
        val kind = if (source == TrackSource.Camera) "camera" else "screen"
        return remoteTracks["$identity:$kind"] as? VideoTrack
    }

    fun initializeRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(egl.eglBaseContext, null)
    }

    fun watch(identity: String, source: TrackSource) {
        val kind = if (source == TrackSource.Camera) "camera" else "screen"
        val key = "$identity:$kind"
        watched += key
        remoteMetadata[identity]?.get(kind)?.let { metadata -> scope.launch { pullTrack(identity, kind, metadata) } }
    }

    fun unwatch(identity: String, source: TrackSource) {
        val kind = if (source == TrackSource.Camera) "camera" else "screen"
        watched -= "$identity:$kind"
    }

    fun setParticipantVolume(identity: String, stream: Boolean, volume: Int) {
        val kind = if (stream) "screenAudio" else "audio"
        (remoteTracks["$identity:$kind"] as? AudioTrack)?.setVolume(volume.coerceIn(0, 100) / 100.0)
    }

    fun send(payload: JSONObject) {
        val message = JSONObject().put("type", "event").put("event", payload)
        socket?.send(message.toString())
    }

    private fun createMicrophoneTrack(enabled: Boolean) {
        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("mhtalk-mic-${UUID.randomUUID()}", source)
        track.setEnabled(enabled)
        audioSource = source
        microphoneTrack = track
    }

    private fun createCameraTrack() {
        val enumerator = Camera2Enumerator(appContext)
        val name = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing)
            ?: enumerator.deviceNames.firstOrNull()
            ?: error("No camera is available")
        val capturer = enumerator.createCapturer(name, null)
            ?: error("Could not open the camera")
        val source = factory.createVideoSource(false)
        val helper = SurfaceTextureHelper.create("CloudflareCamera", egl.eglBaseContext)
        capturer.initialize(helper, appContext, source.capturerObserver)
        capturer.startCapture(1280, 720, 24)
        val track = factory.createVideoTrack("mhtalk-camera-${UUID.randomUUID()}", source)
        track.setEnabled(false)
        cameraCapturer = capturer
        cameraSource = source
        cameraHelper = helper
        cameraTrack = track
    }

    private suspend fun publishLocalTracks(tracks: List<Pair<String, MediaStreamTrack>>) {
        val connection = peer ?: error("Cloudflare peer connection is unavailable")
        val localSession = sessionId ?: error("Cloudflare session is unavailable")
        val transceivers = tracks.map { (kind, track) ->
            val transceiver = connection.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
            )
            Triple(kind, UUID.randomUUID().toString(), transceiver)
        }
        val offer = connection.createOfferAsync()
        connection.setLocalDescriptionAsync(offer)
        waitForIceGathering(connection)
        val body = JSONObject()
            .put("sessionDescription", JSONObject().put("type", "offer").put("sdp", connection.localDescription?.description ?: offer.description))
            .put("tracks", JSONArray().apply {
                transceivers.forEach { (_, trackName, transceiver) ->
                    put(JSONObject().put("trackName", trackName).put("mid", transceiver.mid).put("location", "local"))
                }
            })
        val response = post("${baseUrl()}/partytracks/sessions/$localSession/tracks/new", body)
        response.optJSONObject("sessionDescription")?.let { remote ->
            connection.setRemoteDescriptionAsync(remote.sessionDescription())
        }
        val returned = response.optJSONArray("tracks") ?: JSONArray()
        transceivers.forEach { (kind, trackName, transceiver) ->
            val match = (0 until returned.length()).map(returned::getJSONObject)
                .firstOrNull { it.optString("trackName") == trackName || it.optString("mid") == transceiver.mid }
            if (match != null) {
                localMetadata[kind] = CloudflareTrackMetadata(localSession, match.optString("trackName", trackName), match.optString("mid").takeIf(String::isNotBlank))
            }
        }
        publishState()
    }

    private suspend fun pullTrack(identity: String, kind: String, metadata: CloudflareTrackMetadata) {
        val key = "$identity:$kind"
        if (pulledMetadata[key] == metadata || (kind in setOf("camera", "screen") && key !in watched)) return
        val connection = peer ?: return
        val localSession = sessionId ?: return
        val response = post(
            "${baseUrl()}/partytracks/sessions/$localSession/tracks/new",
            JSONObject().put("tracks", JSONArray().put(metadata.json())),
        )
        val tracks = response.optJSONArray("tracks") ?: return
        val returned = (0 until tracks.length()).map(tracks::getJSONObject)
            .firstOrNull { it.optString("trackName") == metadata.trackName } ?: return
        val mid = returned.optString("mid").takeIf(String::isNotBlank) ?: return
        if (response.optBoolean("requiresImmediateRenegotiation")) {
            val remote = response.requireObject("sessionDescription")
            connection.setRemoteDescriptionAsync(remote.sessionDescription())
            val answer = connection.createAnswerAsync()
            connection.setLocalDescriptionAsync(answer)
            waitForIceGathering(connection)
            post(
                "${baseUrl()}/partytracks/sessions/$localSession/renegotiate",
                JSONObject().put(
                    "sessionDescription",
                    JSONObject().put("type", "answer").put("sdp", connection.localDescription?.description ?: answer.description),
                ),
                method = "PUT",
            )
        }
        repeat(20) {
            val track = connection.transceivers.firstOrNull { it.mid == mid }?.receiver?.track()
            if (track != null) {
                remoteTracks[key] = track
                pulledMetadata[key] = metadata
                if (track is AudioTrack) track.setVolume(1.0)
                return
            }
            delay(50)
        }
    }

    private fun openRoomSocket(credentials: RoomCredentials) {
        val url = credentials.serverUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/room?ticket=${credentials.token}"
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketOpened.complete(Unit)
                publishState()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socketOpened.isActive) socketOpened.completeExceptionally(t)
                else onConnectionState(ConnectionState.Failed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (credentials == this@CloudflareRtcSession.credentials) onConnectionState(ConnectionState.Failed)
            }
        })
    }

    private fun handleMessage(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.optString("type") == "event") {
            val sender = message.optString("identity")
            val event = message.optJSONObject("event")
            if (sender.isNotBlank() && sender != identity && event != null) onPayload(sender, event)
            return
        }
        if (message.optString("type") != "snapshot") return
        val array = message.optJSONArray("members") ?: return
        val next = linkedMapOf<String, CloudflareMember>()
        val nextMetadata = mutableMapOf<String, Map<String, CloudflareTrackMetadata>>()
        for (index in 0 until array.length()) {
            val value = array.optJSONObject(index) ?: continue
            val userId = value.optString("identity")
            if (userId.isBlank() || userId == identity) continue
            val media = value.optJSONObject("media") ?: JSONObject()
            next[userId] = CloudflareMember(
                identity = userId,
                microphoneEnabled = media.optBoolean("microphoneEnabled"),
                cameraEnabled = media.optBoolean("cameraEnabled"),
                screenShareEnabled = media.optBoolean("screenShareEnabled"),
            )
            val tracks = value.optJSONObject("tracks") ?: JSONObject()
            nextMetadata[userId] = listOf("audio", "camera", "screen", "screenAudio").mapNotNull { kind ->
                val track = tracks.optJSONObject(kind) ?: return@mapNotNull null
                val remoteSession = track.optString("sessionId")
                val name = track.optString("trackName")
                if (remoteSession.isBlank() || name.isBlank()) null else kind to CloudflareTrackMetadata(remoteSession, name)
            }.toMap()
        }
        members.clear()
        members.putAll(next)
        remoteMetadata.clear()
        remoteMetadata.putAll(nextMetadata)
        nextMetadata.forEach { (userId, tracks) ->
            tracks["audio"]?.let { scope.launch { pullTrack(userId, "audio", it) } }
            tracks["screenAudio"]?.let { scope.launch { pullTrack(userId, "screenAudio", it) } }
            listOf("camera", "screen").forEach { kind ->
                if ("$userId:$kind" in watched) tracks[kind]?.let { scope.launch { pullTrack(userId, kind, it) } }
            }
        }
        onMembers(next.values.toList())
    }

    private fun publishState() {
        if (!socketOpened.isCompleted) return
        val payload = JSONObject()
            .put("type", "publish")
            .put("tracks", JSONObject().apply { localMetadata.forEach { (kind, metadata) -> put(kind, metadata.json()) } })
            .put(
                "media",
                JSONObject()
                    .put("microphoneEnabled", microphoneEnabled)
                    .put("cameraEnabled", cameraEnabled)
                    .put("screenShareEnabled", screenEnabled),
            )
        socket?.send(payload.toString())
    }

    private suspend fun post(url: String, body: JSONObject, method: String = "POST") = withContext(Dispatchers.IO) {
        val accountToken = accessToken()?.takeIf(String::isNotBlank)
            ?: error("Sign in is required for Cloudflare Realtime")
        val requestBody = body.toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accountToken")
            .header("Content-Type", "application/json")
            .method(method, requestBody)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IllegalStateException("Cloudflare negotiation failed (${response.code})")
            JSONObject(text.ifBlank { "{}" })
        }
    }

    private fun baseUrl() = credentials?.serverUrl?.trimEnd('/')
        ?: error("Cloudflare Realtime is not connected")

    private val peerObserver = object : PeerConnection.Observer {
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> onConnectionState(ConnectionState.Connected)
                PeerConnection.PeerConnectionState.DISCONNECTED -> onConnectionState(ConnectionState.Reconnecting)
                PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED -> onConnectionState(ConnectionState.Failed)
                else -> Unit
            }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
        override fun onTrack(transceiver: RtpTransceiver) = Unit
    }

    private suspend fun PeerConnection.createOfferAsync() = createSdp { observer -> createOffer(observer, MediaConstraints()) }
    private suspend fun PeerConnection.createAnswerAsync() = createSdp { observer -> createAnswer(observer, MediaConstraints()) }

    private suspend fun createSdp(start: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { continuation ->
        start(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
            override fun onCreateFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        })
    }

    private suspend fun PeerConnection.setLocalDescriptionAsync(description: SessionDescription) = setSdp { observer -> setLocalDescription(observer, description) }
    private suspend fun PeerConnection.setRemoteDescriptionAsync(description: SessionDescription) = setSdp { observer -> setRemoteDescription(observer, description) }

    private suspend fun setSdp(start: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { continuation ->
        start(object : SdpObserver {
            override fun onSetSuccess() = continuation.resume(Unit)
            override fun onSetFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
        })
    }

    private suspend fun waitForIceGathering(connection: PeerConnection) {
        repeat(40) {
            if (connection.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return
            delay(50)
        }
    }

    private fun JSONObject.sessionDescription(): SessionDescription {
        val type = when (requireString("type").lowercase()) {
            "answer" -> SessionDescription.Type.ANSWER
            "offer" -> SessionDescription.Type.OFFER
            else -> error("Unsupported Cloudflare session description")
        }
        return SessionDescription(type, requireString("sdp"))
    }

    private fun JSONObject.requireString(key: String) = optString(key).takeIf(String::isNotBlank)
        ?: error("Cloudflare response is missing $key")

    private fun JSONObject.requireObject(key: String) = optJSONObject(key)
        ?: error("Cloudflare response is missing $key")
}
