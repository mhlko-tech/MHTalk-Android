package com.mhlko.talk.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.view.View
import com.mhlko.talk.data.RoomCredentials
import com.mhlko.talk.data.ShareQuality
import com.mhlko.talk.data.SubscriptionTier
import com.mhlko.talk.data.subscriptionEntitlements
import com.tencent.rtmp.ui.TXCloudVideoView
import com.tencent.trtc.TRTCCloud
import com.tencent.trtc.TRTCCloudDef
import com.tencent.trtc.TRTCCloudListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal data class TencentMember(
    val identity: String,
    val speaking: Boolean = false,
    val microphoneEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    val screenShareEnabled: Boolean = false,
)

/** Native Tencent TRTC transport. UserSig is generated only by the MHTalk worker. */
internal class TencentRtcSession(
    private val context: Context,
    private val onMembers: (List<TencentMember>) -> Unit,
    private val onPayload: (String, JSONObject) -> Unit,
    private val onConnectionState: (ConnectionState) -> Unit,
    private val onNetworkQuality: (Int) -> Unit,
    private val onScreenShareStopped: () -> Unit,
) {
    private var rtc: TRTCCloud? = null
    private var credentials: RoomCredentials? = null
    private var joined = CompletableDeferred<Unit>()
    private val members = linkedMapOf<String, TencentMember>()
    private val speaking = mutableSetOf<String>()
    private val watched = mutableSetOf<String>()
    private var frontCamera = true
    private var cameraEnabled = false
    private var screenEnabled = false
    private var localScreenView: TXCloudVideoView? = null

    val connected: Boolean
        get() = rtc != null && joined.isCompleted

    val identity: String?
        get() = credentials?.identity

    val participants: List<TencentMember>
        get() = members.values.map { it.copy(speaking = it.identity in speaking) }

    private val listener = object : TRTCCloudListener() {
        override fun onEnterRoom(result: Long) {
            if (result > 0) joined.complete(Unit)
            else joined.completeExceptionally(IllegalStateException("Tencent rejected the room connection ($result)"))
        }

        override fun onError(errCode: Int, errMsg: String?, extraInfo: android.os.Bundle?) {
            if (joined.isActive) {
                joined.completeExceptionally(IllegalStateException(errMsg ?: "Tencent RTC error $errCode"))
            }
        }

        override fun onRemoteUserEnterRoom(userId: String) {
            member(userId)
            emitMembers()
        }

        override fun onRemoteUserLeaveRoom(userId: String, reason: Int) {
            members.remove(userId)
            speaking.remove(userId)
            watched.removeAll { it.startsWith("$userId:") }
            emitMembers()
        }

        override fun onUserAudioAvailable(userId: String, available: Boolean) {
            patch(userId) { it.copy(microphoneEnabled = available) }
        }

        override fun onUserVideoAvailable(userId: String, available: Boolean) {
            patch(userId) { it.copy(cameraEnabled = available) }
        }

        override fun onUserSubStreamAvailable(userId: String, available: Boolean) {
            patch(userId) { it.copy(screenShareEnabled = available) }
        }

        override fun onUserVoiceVolume(
            userVolumes: ArrayList<TRTCCloudDef.TRTCVolumeInfo>?,
            totalVolume: Int,
        ) {
            speaking.clear()
            userVolumes.orEmpty()
                .filter { it.userId.isNotBlank() && it.volume >= 8 }
                .forEach { speaking += it.userId }
            emitMembers()
        }

        override fun onRecvCustomCmdMsg(
            userId: String,
            cmdId: Int,
            seq: Int,
            message: ByteArray?,
        ) {
            if (userId.isBlank() || userId == identity || message == null) return
            runCatching { JSONObject(message.toString(Charsets.UTF_8)) }
                .onSuccess { onPayload(userId, it) }
        }

        override fun onNetworkQuality(
            localQuality: TRTCCloudDef.TRTCQuality?,
            remoteQuality: ArrayList<TRTCCloudDef.TRTCQuality>?,
        ) {
            val worst = sequenceOf(localQuality?.quality)
                .plus(remoteQuality.orEmpty().asSequence().map { it.quality })
                .filterNotNull()
                .maxOrNull() ?: TRTCCloudDef.TRTC_QUALITY_UNKNOWN
            onNetworkQuality(worst)
        }

        override fun onTryToReconnect() = onConnectionState(ConnectionState.Reconnecting)

        override fun onConnectionRecovery() = onConnectionState(ConnectionState.Connected)

        override fun onConnectionLost() = onConnectionState(ConnectionState.Failed)

        override fun onScreenCaptureStopped(reason: Int) {
            screenEnabled = false
            localScreenView = null
            onScreenShareStopped()
        }
    }

    suspend fun connect(credentials: RoomCredentials, microphoneEnabled: Boolean) {
        val sdkAppId = credentials.clientKey?.toIntOrNull()?.takeIf { it > 0 }
            ?: error("Tencent SDK App ID is missing")
        val identity = credentials.identity?.takeIf(String::isNotBlank)
            ?: error("Tencent participant identity is missing")
        disconnect()
        this.credentials = credentials
        joined = CompletableDeferred()
        val cloud = TRTCCloud.sharedInstance(context.applicationContext)
        rtc = cloud
        cloud.addListener(listener)
        cloud.setDefaultStreamRecvMode(true, false)
        cloud.enableAudioVolumeEvaluation(
            true,
            TRTCCloudDef.TRTCAudioVolumeEvaluateParams().apply { interval = 500 },
        )
        cloud.enterRoom(
            TRTCCloudDef.TRTCParams().apply {
                this.sdkAppId = sdkAppId
                userId = identity
                userSig = credentials.token
                strRoomId = credentials.roomName
                role = TRTCCloudDef.TRTCRoleAnchor
            },
            TRTCCloudDef.TRTC_APP_SCENE_VIDEOCALL,
        )
        withTimeout(18_000) { joined.await() }
        if (microphoneEnabled) cloud.startLocalAudio(TRTCCloudDef.TRTC_AUDIO_QUALITY_DEFAULT)
    }

    fun disconnect() {
        val cloud = rtc
        rtc = null
        credentials = null
        members.clear()
        speaking.clear()
        watched.clear()
        cameraEnabled = false
        screenEnabled = false
        localScreenView = null
        if (joined.isActive) joined.cancel()
        if (cloud != null) {
            runCatching { cloud.stopSystemAudioLoopback() }
            runCatching { cloud.stopScreenCapture() }
            runCatching { cloud.stopLocalPreview() }
            runCatching { cloud.stopLocalAudio() }
            runCatching { cloud.exitRoom() }
            cloud.removeListener(listener)
            TRTCCloud.destroySharedInstance()
        }
        emitMembers()
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        val cloud = rtc ?: error("Tencent is not connected")
        if (enabled) cloud.startLocalAudio(TRTCCloudDef.TRTC_AUDIO_QUALITY_DEFAULT)
        else cloud.stopLocalAudio()
    }

    fun setCameraEnabled(enabled: Boolean) {
        val cloud = rtc ?: error("Tencent is not connected")
        if (enabled) {
            cloud.setVideoEncoderParam(cameraEncoder())
            cloud.startLocalPreview(frontCamera, null)
        } else {
            cloud.stopLocalPreview()
        }
        cameraEnabled = enabled
    }

    fun switchCamera() {
        frontCamera = !frontCamera
        rtc?.deviceManager?.switchCamera(frontCamera)
    }

    fun startScreenShare(permissionData: Intent, quality: ShareQuality) {
        val cloud = rtc ?: error("Tencent is not connected")
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(Activity.RESULT_OK, permissionData)
            ?: error("Screen capture permission expired")
        val preview = TXCloudVideoView(context)
        localScreenView = preview
        cloud.startScreenCapture(
            TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_SUB,
            screenEncoder(quality),
            TRTCCloudDef.TRTCScreenShareParams().apply {
                mediaProjection = projection
                floatingView = preview
                enableForegroundService = false
            },
        )
        cloud.startSystemAudioLoopback()
        screenEnabled = true
    }

    fun stopScreenShare() {
        val cloud = rtc ?: return
        cloud.stopSystemAudioLoopback()
        cloud.stopScreenCapture()
        screenEnabled = false
        localScreenView = null
    }

    fun send(payload: JSONObject) {
        val bytes = payload.toString().toByteArray()
        require(bytes.size <= 1_000) { "Tencent room event is too large" }
        check(rtc?.sendCustomCmdMsg(1, bytes, true, true) == true) {
            "Tencent room event failed"
        }
    }

    fun watch(identity: String, source: TrackSource, quality: ShareQuality = ShareQuality.Medium) {
        val cloud = rtc ?: return
        val streamType = source.streamType
        watched += "$identity:${source.name}"
        cloud.muteRemoteVideoStream(identity, streamType, false)
        if (source == TrackSource.Camera) {
            cloud.setRemoteVideoStreamType(
                identity,
                if (quality == ShareQuality.Low) {
                    TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_SMALL
                } else {
                    TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG
                },
            )
        }
    }

    fun unwatch(identity: String, source: TrackSource) {
        watched -= "$identity:${source.name}"
        rtc?.muteRemoteVideoStream(identity, source.streamType, true)
        rtc?.stopRemoteView(identity, source.streamType)
    }

    fun setParticipantVolume(identity: String, volume: Int) {
        rtc?.setRemoteAudioVolume(identity, volume.coerceIn(0, 100))
    }

    fun createVideoView(context: Context, identity: String?, source: TrackSource): View? {
        val cloud = rtc ?: return null
        if (identity == null && source == TrackSource.Screen) return localScreenView
        val view = TXCloudVideoView(context)
        val render = TRTCCloudDef.TRTCRenderParams().apply {
            fillMode = TRTCCloudDef.TRTC_VIDEO_RENDER_MODE_FIT
        }
        if (identity == null) {
            cloud.setLocalRenderParams(render)
            cloud.updateLocalView(view)
        } else {
            cloud.setRemoteRenderParams(identity, source.streamType, render)
            cloud.startRemoteView(identity, source.streamType, view)
        }
        return view
    }

    private fun member(identity: String) = members.getOrPut(identity) { TencentMember(identity) }

    private fun patch(identity: String, operation: (TencentMember) -> TencentMember) {
        members[identity] = operation(member(identity))
        emitMembers()
    }

    private fun emitMembers() = onMembers(participants)

    private fun cameraEncoder() = TRTCCloudDef.TRTCVideoEncParam().apply {
        val maximum = credentials?.subscriptionTier
            ?.let(::subscriptionEntitlements)
            ?.maxCameraQuality ?: ShareQuality.Medium
        videoResolution = if (maximum == ShareQuality.High) {
            TRTCCloudDef.TRTC_VIDEO_RESOLUTION_1920_1080
        } else {
            TRTCCloudDef.TRTC_VIDEO_RESOLUTION_1280_720
        }
        videoResolutionMode = TRTCCloudDef.TRTC_VIDEO_RESOLUTION_MODE_PORTRAIT
        videoFps = 30
        videoBitrate = 0
        minVideoBitrate = 0
        enableAdjustRes = true
    }

    private fun screenEncoder(quality: ShareQuality) = TRTCCloudDef.TRTCVideoEncParam().apply {
        videoResolution = when (quality) {
            ShareQuality.Low -> TRTCCloudDef.TRTC_VIDEO_RESOLUTION_640_360
            ShareQuality.Medium -> TRTCCloudDef.TRTC_VIDEO_RESOLUTION_1280_720
            ShareQuality.High -> TRTCCloudDef.TRTC_VIDEO_RESOLUTION_1920_1080
        }
        videoResolutionMode = TRTCCloudDef.TRTC_VIDEO_RESOLUTION_MODE_LANDSCAPE
        videoFps = if (quality == ShareQuality.Low) 15 else 30
        videoBitrate = 0
        minVideoBitrate = 0
        enableAdjustRes = false
    }

    internal enum class TrackSource(val streamType: Int) {
        Camera(TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_BIG),
        Screen(TRTCCloudDef.TRTC_VIDEO_STREAM_TYPE_SUB),
    }

    internal enum class ConnectionState { Connected, Reconnecting, Failed }
}
