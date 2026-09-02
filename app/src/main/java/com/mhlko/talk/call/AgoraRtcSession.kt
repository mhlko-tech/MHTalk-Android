package com.mhlko.talk.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import com.mhlko.talk.data.RoomCredentials
import com.mhlko.talk.data.ShareQuality
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcConnection
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.ScreenCaptureParameters
import io.agora.rtc2.UserInfo
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

internal data class AgoraMember(
    val identity: String,
    val speaking: Boolean = false,
    val microphoneEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    val screenShareEnabled: Boolean = false,
)

/** Native Agora transport. Credentials are short-lived and never stored on-device. */
internal class AgoraRtcSession(
    private val context: Context,
    private val onMembers: (List<AgoraMember>) -> Unit,
    private val onPayload: (String, JSONObject) -> Unit,
    private val onConnectionState: (Int) -> Unit,
    private val onTokenRefreshNeeded: (Boolean) -> Unit,
    private val onScreenShareStopped: () -> Unit,
) {
    private var engine: RtcEngineEx? = null
    private var credentials: RoomCredentials? = null
    private var mainConnection: RtcConnection? = null
    private var screenConnection: RtcConnection? = null
    private var projection: MediaProjection? = null
    private var stoppingScreenShare = false
    private var dataStreamId = -1
    private val identitiesByUid = mutableMapOf<Int, String>()
    private val remoteByIdentity = mutableMapOf<String, AgoraMember>()
    private val speaking = mutableSetOf<String>()
    private var joined = CompletableDeferred<Unit>()
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            projection = null
            engine?.stopScreenCapture()
            screenConnection?.let { connection -> engine?.leaveChannelEx(connection) }
            screenConnection = null
            if (!stoppingScreenShare) onScreenShareStopped()
        }
    }

    val connected: Boolean
        get() = engine != null && joined.isCompleted

    private val mainEvents = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            mainConnection = RtcConnection(channel.orEmpty(), uid)
            dataStreamId = engine?.createDataStream(true, true) ?: -1
            joined.complete(Unit)
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            identityFor(uid)?.let { identity ->
                remoteByIdentity.putIfAbsent(identity, AgoraMember(identity))
            }
            emitMembers()
        }

        override fun onUserInfoUpdated(uid: Int, userInfo: UserInfo?) {
            val identity = userInfo?.userAccount?.takeIf(String::isNotBlank) ?: return
            identitiesByUid[uid] = identity
            remoteByIdentity.putIfAbsent(baseIdentity(identity), AgoraMember(baseIdentity(identity)))
            emitMembers()
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            val account = identitiesByUid.remove(uid) ?: return
            val identity = baseIdentity(account)
            if (identitiesByUid.values.none { baseIdentity(it) == identity }) {
                remoteByIdentity.remove(identity)
                speaking.remove(identity)
            } else if (isScreenIdentity(account)) {
                patchMember(identity) { it.copy(screenShareEnabled = false) }
            }
            emitMembers()
        }

        override fun onUserMuteAudio(uid: Int, muted: Boolean) {
            identityFor(uid)?.takeUnless(::isScreenIdentity)?.let { account ->
                patchMember(baseIdentity(account)) { it.copy(microphoneEnabled = !muted) }
            }
        }

        override fun onUserMuteVideo(uid: Int, muted: Boolean) {
            identityFor(uid)?.let { account ->
                val identity = baseIdentity(account)
                patchMember(identity) {
                    if (isScreenIdentity(account)) it.copy(screenShareEnabled = !muted)
                    else it.copy(cameraEnabled = !muted)
                }
            }
        }

        override fun onRemoteAudioStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            identityFor(uid)?.takeUnless(::isScreenIdentity)?.let { account ->
                patchMember(baseIdentity(account)) {
                    it.copy(microphoneEnabled = state != Constants.REMOTE_AUDIO_STATE_STOPPED && state != Constants.REMOTE_AUDIO_STATE_FAILED)
                }
            }
        }

        override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            identityFor(uid)?.let { account ->
                val active = state != Constants.REMOTE_VIDEO_STATE_STOPPED && state != Constants.REMOTE_VIDEO_STATE_FAILED
                val identity = baseIdentity(account)
                patchMember(identity) {
                    if (isScreenIdentity(account)) it.copy(screenShareEnabled = active)
                    else it.copy(cameraEnabled = active)
                }
            }
        }

        override fun onAudioVolumeIndication(
            speakers: Array<out AudioVolumeInfo>?,
            totalVolume: Int,
        ) {
            speaking.clear()
            speakers.orEmpty().forEach { info ->
                if (info.uid != 0 && info.volume >= 8) {
                    identityFor(info.uid)?.takeUnless(::isScreenIdentity)?.let {
                        speaking += baseIdentity(it)
                    }
                }
            }
            emitMembers()
        }

        override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray?) {
            val identity = identityFor(uid)?.takeUnless(::isScreenIdentity)?.let(::baseIdentity) ?: return
            val payload = data?.let { runCatching { JSONObject(it.toString(Charsets.UTF_8)) }.getOrNull() } ?: return
            onPayload(identity, payload)
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            onConnectionState(state)
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            onTokenRefreshNeeded(false)
        }
    }

    private val screenEvents = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            screenConnection = RtcConnection(channel.orEmpty(), uid)
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            onTokenRefreshNeeded(true)
        }
    }

    suspend fun connect(credentials: RoomCredentials, microphoneEnabled: Boolean) {
        val appId = credentials.clientKey?.takeIf(String::isNotBlank)
            ?: error("Agora App ID is missing")
        val identity = credentials.identity?.takeIf(String::isNotBlank)
            ?: error("Agora participant identity is missing")
        disconnect()
        this.credentials = credentials
        joined = CompletableDeferred()
        val config = RtcEngineConfig().apply {
            mContext = context.applicationContext
            mAppId = appId
            mEventHandler = mainEvents
            mChannelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            mAudioScenario = Constants.AUDIO_SCENARIO_DEFAULT
        }
        val rtc = RtcEngine.create(config) as RtcEngineEx
        engine = rtc
        rtc.enableAudio()
        rtc.enableVideo()
        rtc.enableAudioVolumeIndication(400, 3, true)
        rtc.setVideoEncoderConfiguration(cameraEncoder())
        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = microphoneEnabled
            publishCameraTrack = false
            autoSubscribeAudio = true
            autoSubscribeVideo = false
        }
        check(rtc.joinChannelWithUserAccount(credentials.token, credentials.roomName, identity, options) == 0) {
            "Agora rejected the room connection"
        }
        joined.await()
    }

    fun disconnect() {
        stoppingScreenShare = true
        runCatching { engine?.stopScreenCapture() }
        runCatching { credentials?.screenIdentity?.let { account ->
            engine?.leaveChannelWithUserAccountEx(credentials?.roomName.orEmpty(), account)
        } }
        runCatching { engine?.leaveChannel() }
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        engine = null
        credentials = null
        mainConnection = null
        screenConnection = null
        identitiesByUid.clear()
        remoteByIdentity.clear()
        speaking.clear()
        dataStreamId = -1
        if (joined.isActive) joined.cancel()
        runCatching { RtcEngine.destroy() }
        stoppingScreenShare = false
        emitMembers()
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        require(engine?.muteLocalAudioStream(!enabled) == 0) { "Agora microphone update failed" }
    }

    fun setCameraEnabled(enabled: Boolean) {
        val rtc = engine ?: error("Agora is not connected")
        if (enabled) {
            rtc.startPreview(Constants.VideoSourceType.VIDEO_SOURCE_CAMERA_PRIMARY)
            require(rtc.muteLocalVideoStream(false) == 0) { "Agora camera update failed" }
        } else {
            rtc.muteLocalVideoStream(true)
            rtc.stopPreview(Constants.VideoSourceType.VIDEO_SOURCE_CAMERA_PRIMARY)
        }
    }

    fun switchCamera() {
        engine?.switchCamera()
    }

    fun startScreenShare(permissionData: Intent, quality: ShareQuality) {
        val rtc = engine ?: error("Agora is not connected")
        val current = credentials ?: error("Agora credentials are missing")
        val screenToken = current.screenToken ?: error("Agora screen token is missing")
        val screenIdentity = current.screenIdentity ?: error("Agora screen identity is missing")
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(Activity.RESULT_OK, permissionData)
            ?: error("Screen capture permission expired")
        projection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        rtc.setExternalMediaProjection(projection)
        val capture = screenCaptureParameters(quality)
        check(rtc.startScreenCapture(capture) == 0) { "Agora could not start screen capture" }
        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = false
            publishCameraTrack = false
            publishScreenCaptureVideo = true
            publishScreenCaptureAudio = true
            autoSubscribeAudio = false
            autoSubscribeVideo = false
        }
        check(
            rtc.joinChannelWithUserAccountEx(
                screenToken,
                current.roomName,
                screenIdentity,
                options,
                screenEvents,
            ) == 0,
        ) { "Agora could not publish screen sharing" }
    }

    fun stopScreenShare() {
        stoppingScreenShare = true
        val current = credentials
        current?.screenIdentity?.let { account ->
            engine?.leaveChannelWithUserAccountEx(current.roomName, account)
        }
        engine?.stopScreenCapture()
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        screenConnection = null
        stoppingScreenShare = false
    }

    fun send(payload: JSONObject) {
        val rtc = engine ?: return
        if (dataStreamId < 0) dataStreamId = rtc.createDataStream(true, true)
        val bytes = payload.toString().toByteArray()
        require(bytes.size <= 1_024) { "Agora room event is too large" }
        require(rtc.sendStreamMessage(dataStreamId, bytes) == 0) { "Agora room event failed" }
    }

    fun renewCredentials(credentials: RoomCredentials) {
        this.credentials = credentials
        credentials.token.takeIf(String::isNotBlank)?.let { engine?.renewToken(it) }
        val connection = screenConnection
        val token = credentials.screenToken
        if (connection != null && !token.isNullOrBlank()) {
            engine?.updateChannelMediaOptionsEx(
                ChannelMediaOptions().apply { this.token = token },
                connection,
            )
        }
    }

    fun watch(identity: String, source: TrackSource, quality: ShareQuality = ShareQuality.Medium) {
        val account = if (source == TrackSource.Screen) "$identity$SCREEN_SUFFIX" else identity
        val uid = uidFor(account) ?: return
        engine?.muteRemoteVideoStream(uid, false)
        engine?.setRemoteVideoStreamType(
            uid,
            if (quality == ShareQuality.Low) Constants.VIDEO_STREAM_LOW else Constants.VIDEO_STREAM_HIGH,
        )
    }

    fun unwatch(identity: String, source: TrackSource) {
        val account = if (source == TrackSource.Screen) "$identity$SCREEN_SUFFIX" else identity
        uidFor(account)?.let { engine?.muteRemoteVideoStream(it, true) }
    }

    fun setParticipantVolume(identity: String, stream: Boolean, volume: Int) {
        val account = if (stream) "$identity$SCREEN_SUFFIX" else identity
        uidFor(account)?.let { engine?.adjustUserPlaybackSignalVolume(it, volume.coerceIn(0, 100)) }
    }

    fun createVideoView(context: Context, identity: String?, source: TrackSource): SurfaceView? {
        val rtc = engine ?: return null
        val view = SurfaceView(context)
        if (identity == null) {
            val canvas = VideoCanvas(view).apply {
                renderMode = VideoCanvas.RENDER_MODE_FIT
                sourceType = if (source == TrackSource.Screen) {
                    Constants.VIDEO_SOURCE_SCREEN_PRIMARY
                } else Constants.VIDEO_SOURCE_CAMERA_PRIMARY
            }
            rtc.setupLocalVideo(canvas)
            return view
        }
        val account = if (source == TrackSource.Screen) "$identity$SCREEN_SUFFIX" else identity
        val uid = uidFor(account) ?: return null
        val connection = mainConnection ?: return null
        val canvas = VideoCanvas(view, VideoCanvas.RENDER_MODE_FIT, uid).apply {
            sourceType = Constants.VIDEO_SOURCE_REMOTE
        }
        rtc.setupRemoteVideoEx(canvas, connection)
        return view
    }

    private fun identityFor(uid: Int): String? {
        identitiesByUid[uid]?.let { return it }
        val info = UserInfo()
        if (engine?.getUserInfoByUid(uid, info) == 0 && info.userAccount.isNotBlank()) {
            identitiesByUid[uid] = info.userAccount
            return info.userAccount
        }
        return null
    }

    private fun uidFor(account: String): Int? {
        identitiesByUid.entries.firstOrNull { it.value == account }?.key?.let { return it }
        val info = UserInfo()
        if (engine?.getUserInfoByUserAccount(account, info) == 0 && info.uid != 0) {
            identitiesByUid[info.uid] = account
            return info.uid
        }
        return null
    }

    private fun patchMember(identity: String, patch: (AgoraMember) -> AgoraMember) {
        remoteByIdentity[identity] = patch(remoteByIdentity[identity] ?: AgoraMember(identity))
        emitMembers()
    }

    private fun emitMembers() {
        onMembers(
            remoteByIdentity.values.map { member ->
                member.copy(speaking = member.identity in speaking)
            },
        )
    }

    private fun cameraEncoder() = VideoEncoderConfiguration().apply {
        dimensions = VideoEncoderConfiguration.VD_1280x720
        frameRate = 30
        degradationPrefer = VideoEncoderConfiguration.DEGRADATION_PREFERENCE.MAINTAIN_BALANCED
    }

    private fun screenCaptureParameters(quality: ShareQuality) = ScreenCaptureParameters().apply {
        captureAudio = true
        captureVideo = true
        videoCaptureParameters = ScreenCaptureParameters.VideoCaptureParameters().apply {
            width = if (quality == ShareQuality.Low) 640 else if (quality == ShareQuality.High) 1920 else 1280
            height = if (quality == ShareQuality.Low) 360 else if (quality == ShareQuality.High) 1080 else 720
            framerate = if (quality == ShareQuality.Low) 15 else 30
            bitrate = 0
        }
        audioCaptureParameters = ScreenCaptureParameters.AudioCaptureParameters().apply {
            sampleRate = 48_000
            channels = 2
            captureSignalVolume = 100
        }
    }

    internal enum class TrackSource { Camera, Screen }

    private companion object {
        const val SCREEN_SUFFIX = ":screen"
        fun isScreenIdentity(value: String) = value.endsWith(SCREEN_SUFFIX)
        fun baseIdentity(value: String) = value.removeSuffix(SCREEN_SUFFIX)
    }
}
