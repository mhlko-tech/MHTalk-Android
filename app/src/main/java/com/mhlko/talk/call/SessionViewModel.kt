package com.mhlko.talk.call

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.MediaRecorder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Environment
import android.util.Base64
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mhlko.talk.BuildConfig
import com.mhlko.talk.auth.AuthRepository
import com.mhlko.talk.data.ChatMessageUi
import com.mhlko.talk.data.AttachmentUi
import com.mhlko.talk.data.AvatarCropSelection
import com.mhlko.talk.data.calculateAvatarCrop
import com.mhlko.talk.data.ConnectionStatus
import com.mhlko.talk.data.MHTalkApi
import com.mhlko.talk.data.MemberUi
import com.mhlko.talk.data.SessionUiState
import com.mhlko.talk.data.UserProfile
import com.mhlko.talk.data.normalizeRoomAvatar
import com.mhlko.talk.data.ShareQuality
import com.mhlko.talk.data.StartupUpdatePhase
import io.livekit.android.audio.ScreenAudioCapturer
import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.StreamBytesOptions
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.participant.AudioTrackPublishOptions
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.VideoQuality
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import io.livekit.android.renderer.SurfaceViewRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = AuthRepository.get(application)
    private val api = MHTalkApi(auth::accessToken)
    private val preferences = application.getSharedPreferences("mhtalk", 0)
    private val room: Room = LiveKit.create(
        application,
        RoomOptions(
            audioTrackCaptureDefaults = microphoneCaptureOptions(
                preferences.getBoolean("audio.noiseCancellation", true),
            ),
        ),
    )
    private val profiles = mutableMapOf<String, UserProfile>()
    private val userVolumes = mutableMapOf<String, Int>()
    private val streamVolumes = mutableMapOf<String, Int>()
    private val remoteTyping = mutableMapOf<String, String>()
    private val typingTimeoutJobs = mutableMapOf<String, Job>()
    private val blockedIdentities = preferences.getStringSet("moderation.blocked", emptySet()).orEmpty().toMutableSet()
    private var localTypingJob: Job? = null
    private var roomEventsJob: Job? = null
    private var countJob: Job? = null
    private var updateJob: Job? = null
    private var wantedRoom: String? = null
    private var wantedInviteCode: String? = null
    private var userLeft = false
    private val attachmentJobs = mutableMapOf<String, Job>()
    private var voiceRecorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var microphoneBeforeVoiceNote = true
    private var screenAudioCapturer: ScreenAudioCapturer? = null
    private var screenAudioTrack: LocalAudioTrack? = null
    private var profileSyncJob: Job? = null
    private val taskRemovedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == CallService.ACTION_TASK_REMOVED) leave()
        }
    }

    private val _state = MutableStateFlow(
        SessionUiState(
            termsAccepted = preferences.getBoolean("legal.termsAccepted", false),
            localProfile = profile,
            outputLevel = preferences.getInt("audio.output", 100),
            noiseCancellationEnabled = preferences.getBoolean("audio.noiseCancellation", true),
            messageSoundsEnabled = preferences.getBoolean("sounds.messages", true),
            presenceSoundsEnabled = preferences.getBoolean("sounds.presence", true),
            cameraSoundsEnabled = preferences.getBoolean("sounds.camera", true),
            screenShareSoundsEnabled = preferences.getBoolean("sounds.screen", true),
            screenSharePrivacyEnabled = preferences.getBoolean("privacy.screenShare", true),
        ),
    )
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    val profile: UserProfile
        get() = UserProfile(
            name = preferences.getString("profile.name", "Me") ?: "Me",
            bio = preferences.getString("profile.bio", "") ?: "",
            avatar = preferences.getString("profile.avatar", "") ?: "",
        )

    fun acceptTerms() {
        preferences.edit().putBoolean("legal.termsAccepted", true).apply()
        _state.update { it.copy(termsAccepted = true) }
    }

    init {
        registerFileReceiver()
        ContextCompat.registerReceiver(application, taskRemovedReceiver, IntentFilter(CallService.ACTION_TASK_REMOVED), ContextCompat.RECEIVER_NOT_EXPORTED)
        collectRoomEvents()
        pollMainCount()
        checkForUpdate()
    }

    fun joinMain() = connect("Main", null)

    fun joinPrivate(code: String) {
        if (code.isBlank()) return
        connect("Private", code.trim().uppercase())
    }

    fun createPrivate() {
        viewModelScope.launch {
            _state.update { it.copy(status = ConnectionStatus.Connecting, error = null) }
            runCatching { api.createPrivateRoom() }
                .onSuccess { private ->
                    // Creating the invite and connecting are two consecutive stages.
                    // Return to idle so the duplicate-tap guard does not block stage two.
                    _state.update { it.copy(status = ConnectionStatus.Idle, privateCode = private.code) }
                    connect(private.roomName, private.code)
                }
                .onFailure(::showFailure)
        }
    }

    fun clearPrivateCode() = _state.update { it.copy(privateCode = null) }
    fun showNotice(message: String) = _state.update { it.copy(notice = message) }

    private fun connect(roomName: String, inviteCode: String?) {
        if (_state.value.status == ConnectionStatus.Connecting) return
        viewModelScope.launch {
            clearRemoteTyping()
            userLeft = false
            wantedRoom = roomName
            wantedInviteCode = inviteCode
            _state.update { it.copy(status = ConnectionStatus.Connecting, error = null) }
            runCatching {
                if (_state.value.roomName != null) room.disconnect()
                val credentials = api.credentials(roomName, inviteCode)
                room.connect(BuildConfig.LIVEKIT_URL, credentials.token)
                startCallService(camera = false, screenShare = false)
                room.localParticipant.setMicrophoneEnabled(_state.value.microphoneEnabled)
                sendProfile()
                credentials.roomName
            }.onSuccess { actualRoom ->
                _state.update {
                    it.copy(
                        status = ConnectionStatus.Connected,
                        roomName = actualRoom,
                        error = null,
                        messages = emptyList(),
                    )
                }
                syncParticipants()
                disableAutoSubscribeForRemoteMedia()
                requestProfiles()
            }.onFailure(::showFailure)
        }
    }

    fun leave() {
        clearRemoteTyping()
        userLeft = true
        wantedRoom = null
        wantedInviteCode = null
        room.disconnect()
        getApplication<Application>().stopService(Intent(getApplication(), CallService::class.java))
        _state.value = SessionUiState(
            termsAccepted = preferences.getBoolean("legal.termsAccepted", false),
            mainActiveCount = _state.value.mainActiveCount,
            localProfile = profile,
            outputLevel = preferences.getInt("audio.output", 100),
            messageSoundsEnabled = preferences.getBoolean("sounds.messages", true),
            presenceSoundsEnabled = preferences.getBoolean("sounds.presence", true),
            cameraSoundsEnabled = preferences.getBoolean("sounds.camera", true),
            screenShareSoundsEnabled = preferences.getBoolean("sounds.screen", true),
            screenSharePrivacyEnabled = preferences.getBoolean("privacy.screenShare", true),
            launchReady = true,
        )
    }

    private fun clearRemoteTyping() {
        typingTimeoutJobs.values.forEach(Job::cancel)
        typingTimeoutJobs.clear()
        remoteTyping.clear()
        _state.update { it.copy(typingNames = emptyList()) }
    }

    fun toggleMicrophone() {
        viewModelScope.launch {
            val enabled = !_state.value.microphoneEnabled
            if (_state.value.status == ConnectionStatus.Connected) {
                runCatching { setMicrophoneState(enabled) }
                    .onFailure(::showFailure)
            } else {
                _state.update { it.copy(microphoneEnabled = enabled) }
            }
        }
    }

    private suspend fun setMicrophoneState(enabled: Boolean) {
        val publication = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)
        if (screenAudioTrack != null && publication is LocalTrackPublication) {
            publication.muted = !enabled
        } else {
            room.localParticipant.setMicrophoneEnabled(enabled)
        }
        _state.update { it.copy(microphoneEnabled = enabled) }
    }

    fun toggleCamera() {
        viewModelScope.launch {
            val enabled = !_state.value.cameraEnabled
            runCatching { room.localParticipant.setCameraEnabled(enabled) }
                .onSuccess {
                    _state.update { it.copy(cameraEnabled = enabled) }
                    startCallService(enabled, _state.value.screenShareEnabled)
                }
                .onFailure(::showFailure)
        }
    }

    fun startScreenShare(permissionData: Intent, includeMicrophone: Boolean, quality: ShareQuality) {
        if (_state.value.status != ConnectionStatus.Connected) return
        viewModelScope.launch {
            configureShareQuality(quality)
            startCallService(_state.value.cameraEnabled, screenShare = true)
            runCatching {
                room.localParticipant.setScreenShareEnabled(
                    true,
                    ScreenCaptureParams(permissionData) {
                        stopScreenAudio()
                        _state.update { it.copy(screenShareEnabled = false) }
                        startCallService(_state.value.cameraEnabled, screenShare = false)
                    },
                )
            }.onSuccess {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startScreenAudio()
                setMicrophoneState(includeMicrophone)
                _state.update { it.copy(screenShareEnabled = true, microphoneEnabled = includeMicrophone) }
                syncParticipants()
            }.onFailure {
                startCallService(_state.value.cameraEnabled, screenShare = false)
                showFailure(it)
            }
        }
    }

    fun stopScreenShare() {
        viewModelScope.launch {
            runCatching {
                stopScreenAudio()
                room.localParticipant.setScreenShareEnabled(false)
            }
                .onSuccess {
                    _state.update { it.copy(screenShareEnabled = false) }
                    startCallService(_state.value.cameraEnabled, screenShare = false)
                    syncParticipants()
                }
                .onFailure(::showFailure)
        }
    }

    fun initializeVideoRenderer(renderer: SurfaceViewRenderer) {
        room.initVideoRenderer(renderer)
    }

    fun videoTrack(identity: String?, source: Track.Source): VideoTrack? {
        val participant = if (identity == null) {
            room.localParticipant
        } else {
            room.remoteParticipants.values.firstOrNull { it.identity?.value == identity }
        } ?: return null
        return participant.getTrackPublication(source)?.track as? VideoTrack
    }

    /** Opt-in media: remote camera/screen video and screen audio remain off until the user chooses to watch. */
    fun watchMemberMedia(identity: String) {
        val participant = room.remoteParticipants.values.firstOrNull { it.identity?.value == identity } ?: return
        listOf(Track.Source.CAMERA, Track.Source.SCREEN_SHARE, Track.Source.SCREEN_SHARE_AUDIO).forEach { source ->
            (participant.getTrackPublication(source) as? RemoteTrackPublication)?.setSubscribed(true)
        }
    }

    fun stopWatchingMemberMedia(identity: String) {
        val participant = room.remoteParticipants.values.firstOrNull { it.identity?.value == identity } ?: return
        listOf(Track.Source.CAMERA, Track.Source.SCREEN_SHARE, Track.Source.SCREEN_SHARE_AUDIO).forEach { source ->
            (participant.getTrackPublication(source) as? RemoteTrackPublication)?.setSubscribed(false)
        }
    }

    fun setMemberVideoQuality(identity: String, source: Track.Source, quality: VideoQuality) {
        val participant = room.remoteParticipants.values.firstOrNull { it.identity?.value == identity } ?: return
        (participant.getTrackPublication(source) as? RemoteTrackPublication)?.setVideoQuality(quality)
    }

    private fun configureShareQuality(quality: ShareQuality) {
        val preset = when (quality) {
            ShareQuality.Low -> io.livekit.android.room.track.ScreenSharePresets.H360_FPS15
            ShareQuality.Medium -> io.livekit.android.room.track.ScreenSharePresets.H720_FPS15
            ShareQuality.High -> io.livekit.android.room.track.ScreenSharePresets.H1080_FPS30
        }
        room.screenShareTrackCaptureDefaults = io.livekit.android.room.track.LocalVideoTrackOptions(
            isScreencast = true,
            captureParams = preset.capture,
        )
        room.screenShareTrackPublishDefaults = io.livekit.android.room.participant.VideoTrackPublishDefaults(
            videoEncoding = preset.encoding,
            simulcast = true,
        )
    }

    /** The actual capture dimensions, including a live device rotation during screen sharing. */
    fun videoAspectRatio(identity: String?, source: Track.Source): Float? {
        val participant = if (identity == null) {
            room.localParticipant
        } else {
            room.remoteParticipants.values.firstOrNull { it.identity?.value == identity }
        } ?: return null
        val publication = participant.getTrackPublication(source) ?: return null
        val dimensions = publication.dimensions
            ?: (publication.track as? LocalVideoTrack)?.dimensions
            ?: return null
        if (dimensions.width <= 0 || dimensions.height <= 0) return null
        return dimensions.width.toFloat() / dimensions.height.toFloat()
    }

    fun setParticipantVolume(identity: String, stream: Boolean, volume: Int) {
        val safeVolume = volume.coerceIn(0, 100)
        if (stream) streamVolumes[identity] = safeVolume else userVolumes[identity] = safeVolume
        val participant = room.remoteParticipants.values.firstOrNull { it.identity?.value == identity }
        if (participant == null) {
            syncParticipants()
            return
        }
        val source = if (stream) Track.Source.SCREEN_SHARE_AUDIO else Track.Source.MICROPHONE
        participant.trackPublications.values
            .filter { it.source == source }
            .mapNotNull { it.track as? RemoteAudioTrack }
            .forEach { it.setVolume(safeVolume / 100.0) }
        syncParticipants()
    }

    fun reportUser(identity: String, message: ChatMessageUi? = null) {
        val roomName = _state.value.roomName ?: return
        val reporter = room.localParticipant.identity?.value ?: "android-user"
        viewModelScope.launch {
            runCatching { api.report(roomName, reporter, identity, message?.id, message?.body) }
                .onSuccess { _state.update { it.copy(notice = "Report received. Thank you for helping keep MHTalk safe.") } }
                .onFailure(::showFailure)
        }
    }

    fun blockUser(identity: String) {
        blockedIdentities += identity
        preferences.edit().putStringSet("moderation.blocked", blockedIdentities.toSet()).apply()
        setParticipantVolume(identity, stream = false, volume = 0)
        setParticipantVolume(identity, stream = true, volume = 0)
        remoteTyping.remove(identity)
        _state.update { current ->
            current.copy(
                members = current.members.filterNot { it.identity == identity },
                messages = current.messages.filterNot { it.senderIdentity == identity },
                typingNames = remoteTyping.values.distinct(),
                notice = "User blocked on this device.",
            )
        }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun sendMessage(rawText: String, replyTo: ChatMessageUi? = null) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty() || _state.value.status != ConnectionStatus.Connected) return
        viewModelScope.launch {
            val text = if (_state.value.roomName == "Main") {
                runCatching { api.moderate(trimmed) }.getOrDefault(trimmed)
            } else trimmed
            val message = ChatMessageUi(
                id = UUID.randomUUID().toString(),
                sender = profile.name,
                body = text,
                createdAt = System.currentTimeMillis(),
                mine = true,
                replyToId = replyTo?.id,
                replyToSender = replyTo?.sender,
                replyToBody = replyTo?.body,
            )
            val payload = JSONObject()
                .put("type", "chat")
                .put("id", message.id)
                .put("body", message.body)
                .put("createdAt", message.createdAt)
            if (replyTo != null) {
                payload.put(
                    "replyTo",
                    JSONObject().put("id", replyTo.id).put("sender", replyTo.sender).put("body", replyTo.body),
                )
            }
            runCatching {
                room.localParticipant.publishData(
                    payload.toString().toByteArray(),
                    topic = "mhtalk.chat",
                ).getOrThrow()
            }.onSuccess {
                _state.update { it.copy(messages = it.messages + message) }
            }.onFailure(::showFailure)
        }
    }

    fun updateTyping(isTyping: Boolean) {
        if (_state.value.status != ConnectionStatus.Connected) return
        localTypingJob?.cancel()
        viewModelScope.launch {
            publishTyping(isTyping)
        }
        if (isTyping) {
            localTypingJob = viewModelScope.launch {
                delay(1_500)
                publishTyping(false)
            }
        }
    }

    private suspend fun publishTyping(typing: Boolean) {
        val payload = JSONObject().put("type", "typing").put("typing", typing)
        runCatching {
            room.localParticipant.publishData(payload.toString().toByteArray(), topic = "mhtalk.chat").getOrThrow()
        }
    }

    fun editMessage(messageId: String, rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || _state.value.status != ConnectionStatus.Connected) return
        viewModelScope.launch {
            val filtered = if (_state.value.roomName == "Main") runCatching { api.moderate(text) }.getOrDefault(text) else text
            val payload = JSONObject().put("type", "edit").put("id", messageId).put("body", filtered)
            room.localParticipant.publishData(payload.toString().toByteArray(), topic = "mhtalk.chat").getOrThrow()
            _state.update { current ->
                current.copy(messages = current.messages.map { if (it.id == messageId) it.copy(body = filtered) else it })
            }
        }
    }

    fun deleteMessage(messageId: String) {
        if (_state.value.status != ConnectionStatus.Connected) return
        viewModelScope.launch {
            val payload = JSONObject().put("type", "delete").put("id", messageId)
            room.localParticipant.publishData(payload.toString().toByteArray(), topic = "mhtalk.chat").getOrThrow()
            _state.update { current ->
                current.copy(messages = current.messages.map { if (it.id == messageId) it.copy(body = "", attachment = null, deleted = true) else it })
            }
        }
    }

    fun toggleVoiceRecording() {
        if (_state.value.status != ConnectionStatus.Connected) return
        if (_state.value.isRecordingVoice) stopVoiceRecording() else startVoiceRecording()
    }

    @Suppress("DEPRECATION")
    private fun startVoiceRecording() {
        viewModelScope.launch {
            runCatching {
                microphoneBeforeVoiceNote = _state.value.microphoneEnabled
                if (microphoneBeforeVoiceNote) room.localParticipant.setMicrophoneEnabled(false)
                delay(180)
                val directory = File(getApplication<Application>().cacheDir, "voice").apply { mkdirs() }
                val file = File(directory, "voice-${System.currentTimeMillis()}.m4a")
                val recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(48_000)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                voiceFile = file
                voiceRecorder = recorder
                _state.update { it.copy(isRecordingVoice = true) }
            }.onFailure {
                if (microphoneBeforeVoiceNote) room.localParticipant.setMicrophoneEnabled(true)
                showFailure(it)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopVoiceRecording() {
        viewModelScope.launch {
            val recorder = voiceRecorder
            val file = voiceFile
            voiceRecorder = null
            voiceFile = null
            _state.update { it.copy(isRecordingVoice = false) }
            runCatching { recorder?.stop() }
            recorder?.release()
            if (microphoneBeforeVoiceNote) room.localParticipant.setMicrophoneEnabled(true)
            if (file != null && file.exists() && file.length() > 0) {
                val uri = FileProvider.getUriForFile(getApplication(), "com.mhlko.talk.files", file)
                sendAttachment(uri)
            }
        }
    }

    fun sendAttachment(uri: Uri) {
        if (_state.value.status != ConnectionStatus.Connected) return
        val context = getApplication<Application>()
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        var name = "Attachment"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cursor.getLong(it) }
            }
        }
        val id = UUID.randomUUID().toString()
        val initial = ChatMessageUi(
            id = id,
            sender = profile.name,
            body = "",
            createdAt = System.currentTimeMillis(),
            mine = true,
            attachment = AttachmentUi(uri.toString(), name, mimeType, size, progress = 0f, sending = true),
        )
        _state.update { it.copy(messages = it.messages + initial) }
        attachmentJobs[id] = viewModelScope.launch {
            runCatching {
                val sender = room.localParticipant.streamBytes(
                    StreamBytesOptions(
                        topic = "mhtalk.file",
                        streamId = id,
                        mimeType = mimeType,
                        name = name,
                        totalSize = size.takeIf { it >= 0 },
                    ),
                )
                try {
                    resolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var sent = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            sender.write(buffer.copyOf(count)).getOrThrow()
                            sent += count
                            val progress = if (size > 0) (sent.toFloat() / size).coerceIn(0f, 1f) else 0f
                            patchAttachment(id) { it.copy(progress = progress) }
                        }
                    } ?: error("Could not read attachment")
                    sender.close()
                } catch (error: Throwable) {
                    if (sender.isOpen) sender.close(error.message)
                    throw error
                }
            }.onSuccess {
                patchAttachment(id) { it.copy(progress = 1f, sending = false) }
            }.onFailure { error ->
                if (error !is CancellationException) showFailure(error)
                _state.update { current -> current.copy(messages = current.messages.filterNot { it.id == id }) }
            }
            attachmentJobs.remove(id)
        }
    }

    fun cancelAttachment(id: String) {
        attachmentJobs.remove(id)?.cancel()
    }

    fun saveAttachmentToDownloads(attachment: AttachmentUi) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            _state.update { it.copy(notice = "Saving directly to Downloads requires Android 10 or newer") }
            return
        }
        viewModelScope.launch {
            runCatching {
                saveAttachmentWithMediaStore(attachment)
            }.onSuccess { _state.update { it.copy(notice = "Saved to Downloads") } }.onFailure(::showFailure)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveAttachmentWithMediaStore(attachment: AttachmentUi) {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, attachment.name)
            put(MediaStore.Downloads.MIME_TYPE, attachment.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create download")
        try {
            val input = resolver.openInputStream(Uri.parse(attachment.uri))
                ?: error("Could not open the attachment")
            val output = resolver.openOutputStream(destination)
                ?: error("Could not open the Downloads destination")
            input.use { source ->
                output.use { destinationStream -> source.copyTo(destinationStream) }
            }
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    private fun patchAttachment(id: String, transform: (AttachmentUi) -> AttachmentUi) {
        _state.update { current ->
            current.copy(messages = current.messages.map { message ->
                if (message.id == id && message.attachment != null) message.copy(attachment = transform(message.attachment)) else message
            })
        }
    }

    private fun registerFileReceiver() {
        room.registerByteStreamHandler("mhtalk.file") { reader, identity ->
            viewModelScope.launch {
                if (identity.value in blockedIdentities) return@launch
                runCatching {
                    val directory = File(getApplication<Application>().cacheDir, "attachments").apply { mkdirs() }
                    val safeName = (reader.info.name ?: "Attachment").replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
                    val file = File(directory, "${reader.info.id}-$safeName")
                    val id = identity.value
                    val size = reader.info.totalSize ?: -1L
                    val message = ChatMessageUi(
                        id = reader.info.id,
                        sender = profiles[id]?.name ?: id.take(16),
                        senderIdentity = id,
                        body = "",
                        createdAt = reader.info.timestampMs,
                        mine = false,
                        attachment = AttachmentUi(
                            uri = FileProvider.getUriForFile(getApplication(), "com.mhlko.talk.files", file).toString(),
                            name = reader.info.name ?: "Attachment",
                            mimeType = reader.info.mimeType,
                            size = size,
                            progress = 0f,
                            sending = true,
                        ),
                    )
                    _state.update { it.copy(messages = it.messages + message) }
                    var received = 0L
                    FileOutputStream(file).use { output ->
                        reader.flow.collect { chunk ->
                            output.write(chunk)
                            received += chunk.size
                            if (size > 0) patchAttachment(reader.info.id) {
                                it.copy(progress = (received.toFloat() / size).coerceIn(0f, 1f))
                            }
                        }
                    }
                    patchAttachment(reader.info.id) { it.copy(size = file.length(), progress = 1f, sending = false) }
                }.onFailure { /* isolate a failed transfer from the call */ }
            }
        }
    }

    fun saveProfile(newProfile: UserProfile, syncAccount: Boolean = true) {
        val normalized = newProfile.copy(
            name = newProfile.name.trim().ifBlank { "Me" },
            bio = newProfile.bio.trim(),
        )
        preferences.edit()
            .putString("profile.name", normalized.name)
            .putString("profile.bio", normalized.bio)
            .putString("profile.avatar", normalized.avatar)
            .apply()
        _state.update { it.copy(localProfile = normalized) }
        viewModelScope.launch { sendProfile() }
        syncParticipants()
        if (syncAccount && auth.state.value is com.mhlko.talk.auth.AuthState.SignedIn) {
            profileSyncJob?.cancel()
            profileSyncJob = viewModelScope.launch {
                delay(400)
                runCatching { auth.updateProfile(normalized.name, normalized.bio) }
                    .onFailure { error ->
                        if (error !is CancellationException) showFailure(error)
                    }
            }
        }
    }

    fun chooseProfilePhoto(uri: Uri, selection: AvatarCropSelection) {
        _state.update { it.copy(profilePhotoSaving = true) }
        viewModelScope.launch {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val mimeType = resolver.getType(uri) ?: "image/jpeg"
                require(mimeType.startsWith("image/")) { "Choose an image file" }
                if (mimeType == "image/gif") {
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Could not read profile image")
                    require(bytes.size <= 6 * 1024 * 1024) { "Animated profile image must be 6 MB or smaller" }
                    "data:$mimeType;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Could not decode profile image" }
                    var sample = 1
                    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    val source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                        ?: error("Could not decode profile image")
                    val exif = runCatching {
                        resolver.openInputStream(uri)?.use(::ExifInterface)
                    }.getOrNull()
                    val normalizedRotation = ((selection.rotation + (exif?.rotationDegrees ?: 0)) % 360 + 360) % 360
                    val flipped = exif?.isFlipped == true
                    val oriented = if (normalizedRotation == 0 && !flipped) source else Bitmap.createBitmap(
                        source, 0, 0, source.width, source.height,
                        Matrix().apply {
                            if (flipped) postScale(-1f, 1f)
                            postRotate(normalizedRotation.toFloat())
                        }, true,
                    )
                    val crop = calculateAvatarCrop(oriented.width, oriented.height, selection)
                    val cropped = Bitmap.createBitmap(oriented, crop.left, crop.top, crop.side, crop.side)
                    val avatar = Bitmap.createScaledBitmap(cropped, 512, 512, true)
                    val output = ByteArrayOutputStream()
                    avatar.compress(Bitmap.CompressFormat.JPEG, 90, output)
                    if (avatar !== cropped) avatar.recycle()
                    if (cropped !== oriented) cropped.recycle()
                    if (oriented !== source) oriented.recycle()
                    source.recycle()
                    "data:image/jpeg;base64,${Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)}"
                }
            }.mapCatching { avatar ->
                val current = profile
                val canonicalAvatar = if (auth.state.value is com.mhlko.talk.auth.AuthState.SignedIn) {
                    auth.updateProfile(current.name, current.bio, avatar) ?: avatar
                } else avatar
                saveProfile(current.copy(avatar = canonicalAvatar), syncAccount = false)
                _state.update { it.copy(profilePhotoRevision = it.profilePhotoRevision + 1) }
            }.onFailure { error ->
                if (error !is CancellationException) showFailure(error)
            }
            _state.update { it.copy(profilePhotoSaving = false) }
        }
    }

    fun removeProfilePhoto() {
        viewModelScope.launch {
            _state.update { it.copy(profilePhotoSaving = true) }
            runCatching {
                if (auth.state.value is com.mhlko.talk.auth.AuthState.SignedIn) {
                    auth.updateProfile(profile.name, profile.bio, "")
                }
                saveProfile(profile.copy(avatar = ""), syncAccount = false)
            }.onFailure { error ->
                if (error !is CancellationException) showFailure(error)
            }
            _state.update { it.copy(profilePhotoSaving = false) }
        }
    }

    fun setOutputLevel(level: Int) {
        val value = level.coerceIn(0, 100)
        preferences.edit().putInt("audio.output", value).apply()
        _state.update { it.copy(outputLevel = value) }
        val manager = getApplication<Application>().getSystemService(AudioManager::class.java)
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max * value / 100f).toInt(), 0)
        room.setSpeakerMute(value == 0)
    }

    fun testSpeaker() {
        viewModelScope.launch {
            val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, _state.value.outputLevel)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 450)
            delay(500)
            tone.release()
        }
    }

    fun setMessageSounds(enabled: Boolean) = setBooleanPreference("sounds.messages", enabled) { it.copy(messageSoundsEnabled = enabled) }
    fun setPresenceSounds(enabled: Boolean) = setBooleanPreference("sounds.presence", enabled) {
        it.copy(presenceSoundsEnabled = enabled)
    }.also { if (enabled) playEventTone(4) }
    fun setCameraSounds(enabled: Boolean) = setBooleanPreference("sounds.camera", enabled) { it.copy(cameraSoundsEnabled = enabled) }
    fun setScreenShareSounds(enabled: Boolean) = setBooleanPreference("sounds.screen", enabled) { it.copy(screenShareSoundsEnabled = enabled) }
    fun setScreenSharePrivacy(enabled: Boolean) = setBooleanPreference("privacy.screenShare", enabled) { it.copy(screenSharePrivacyEnabled = enabled) }
    fun setNoiseCancellation(enabled: Boolean) {
        preferences.edit().putBoolean("audio.noiseCancellation", enabled).apply()
        _state.update { it.copy(noiseCancellationEnabled = enabled) }
        val options = microphoneCaptureOptions(enabled)
        room.audioTrackCaptureDefaults = options
        viewModelScope.launch {
            val track = room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack
                ?: return@launch
            runCatching { track.applyOptions(options) }
                .onFailure { error -> if (error !is CancellationException) showFailure(error) }
        }
    }

    private fun setBooleanPreference(key: String, value: Boolean, update: (SessionUiState) -> SessionUiState) {
        preferences.edit().putBoolean(key, value).apply()
        _state.update(update)
    }

    private fun playEventTone(kind: Int) {
        val enabled = when (kind) {
            1 -> _state.value.messageSoundsEnabled
            2 -> _state.value.cameraSoundsEnabled
            3, 6 -> _state.value.screenShareSoundsEnabled
            4, 5 -> _state.value.presenceSoundsEnabled
            else -> true
        }
        if (!enabled) return
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 72)
        tone.startTone(
            when (kind) {
                1 -> ToneGenerator.TONE_PROP_BEEP2
                2 -> ToneGenerator.TONE_PROP_ACK
                3 -> ToneGenerator.TONE_PROP_BEEP
                4 -> ToneGenerator.TONE_PROP_PROMPT
                5 -> ToneGenerator.TONE_PROP_NACK
                else -> ToneGenerator.TONE_PROP_BEEP2
            },
            190,
        )
        viewModelScope.launch { delay(260); tone.release() }
    }

    private data class RemoteUpdate(val version: String, val apkUrl: String)

    fun retryUpdateCheck() = checkForUpdate()

    private fun checkForUpdate() {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    launchReady = false,
                    launchUpdatePhase = StartupUpdatePhase.Checking,
                    launchUpdateProgress = null,
                    launchUpdateMessage = "Checking for updates",
                    updateApkPath = null,
                )
            }
            runCatching {
                val remote = withContext(Dispatchers.IO) { fetchLatestUpdate() }
                if (remote == null || !isNewerVersion(remote.version, BuildConfig.VERSION_NAME)) {
                    _state.update {
                        it.copy(
                            launchReady = true,
                            launchUpdatePhase = StartupUpdatePhase.Complete,
                            launchUpdateProgress = 100,
                            launchUpdateMessage = "MHTalk is up to date",
                            updateVersion = null,
                        )
                    }
                    return@runCatching
                }
                _state.update {
                    it.copy(
                        launchUpdatePhase = StartupUpdatePhase.Downloading,
                        launchUpdateProgress = 0,
                        launchUpdateMessage = "Downloading MHTalk ${remote.version}",
                        updateVersion = remote.version,
                    )
                }
                val apk = withContext(Dispatchers.IO) { downloadUpdate(remote) }
                _state.update {
                    it.copy(
                        launchUpdatePhase = StartupUpdatePhase.ReadyToInstall,
                        launchUpdateProgress = 100,
                        launchUpdateMessage = "Update downloaded. Install to continue.",
                        updateApkPath = apk.absolutePath,
                    )
                }
            }.onFailure {
                _state.update { current ->
                    current.copy(
                        launchReady = false,
                        launchUpdatePhase = StartupUpdatePhase.Error,
                        launchUpdateProgress = null,
                        launchUpdateMessage = "Could not check for updates. Check your internet connection and retry.",
                    )
                }
            }
        }
    }

    private fun fetchLatestUpdate(): RemoteUpdate? {
        val connection = java.net.URL("https://api.github.com/repos/mhlko-tech/MHTalk-Android/releases/latest").openConnection()
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "MHTalk-Android/${BuildConfig.VERSION_NAME}")
        val release = connection.getInputStream().bufferedReader().use { JSONObject(it.readText()) }
        val version = release.optString("tag_name").trim().removePrefix("v")
        if (version.isBlank()) return null
        val assets = release.optJSONArray("assets") ?: return null
        val apkUrl = (0 until assets.length())
            .asSequence()
            .map(assets::getJSONObject)
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?.optString("browser_download_url")
            .orEmpty()
        if (apkUrl.isBlank()) throw IllegalStateException("The Android update package is missing")
        return RemoteUpdate(version, apkUrl)
    }

    private fun downloadUpdate(remote: RemoteUpdate): File {
        val updateDirectory = File(getApplication<Application>().cacheDir, "updates").apply { mkdirs() }
        val partial = File(updateDirectory, "MHTalk-${remote.version}.apk.part")
        val destination = File(updateDirectory, "MHTalk-${remote.version}.apk")
        val connection = java.net.URL(remote.apkUrl).openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "MHTalk-Android/${BuildConfig.VERSION_NAME}")
        }
        val total = connection.contentLengthLong.takeIf { it > 0 }
        connection.getInputStream().use { input ->
            FileOutputStream(partial, false).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                var lastProgress = -1
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    val progress = total?.let { ((downloaded * 100) / it).toInt().coerceIn(0, 99) }
                    if (progress != null && progress != lastProgress) {
                        lastProgress = progress
                        _state.update { it.copy(launchUpdateProgress = progress) }
                    }
                }
            }
        }
        if (destination.exists() && !destination.delete()) throw IllegalStateException("Could not replace the old update")
        if (!partial.renameTo(destination)) throw IllegalStateException("Could not prepare the update")
        return destination
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val a = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        return (0 until maxOf(a.size, b.size)).firstOrNull { (a.getOrElse(it) { 0 }) != (b.getOrElse(it) { 0 }) }
            ?.let { a.getOrElse(it) { 0 } > b.getOrElse(it) { 0 } } ?: false
    }

    fun switchCamera() {
        (room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? io.livekit.android.room.track.LocalVideoTrack)
            ?.switchCamera()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun collectRoomEvents() {
        roomEventsJob?.cancel()
        roomEventsJob = viewModelScope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected,
                    is RoomEvent.ActiveSpeakersChanged,
                    is RoomEvent.TrackUnpublished,
                    is RoomEvent.TrackUnsubscribed,
                    is RoomEvent.TrackMuted,
                    is RoomEvent.TrackUnmuted,
                    is RoomEvent.ParticipantMetadataChanged -> syncParticipants()

                    is RoomEvent.TrackPublished -> {
                        if (event.participant != room.localParticipant && event.publication.source in setOf(Track.Source.CAMERA, Track.Source.SCREEN_SHARE, Track.Source.SCREEN_SHARE_AUDIO)) {
                            (event.publication as? RemoteTrackPublication)?.setSubscribed(false)
                        }
                        syncParticipants()
                    }

                    is RoomEvent.TrackSubscribed -> {
                        val identity = event.participant.identity?.value
                        val audio = event.track as? RemoteAudioTrack
                        if (identity != null && audio != null) {
                            val volume = if (event.publication.source == Track.Source.SCREEN_SHARE_AUDIO) {
                                streamVolumes[identity] ?: 100
                            } else {
                                userVolumes[identity] ?: 100
                            }
                            audio.setVolume(volume / 100.0)
                        }
                        syncParticipants()
                    }

                    is RoomEvent.Reconnecting -> _state.update { it.copy(status = ConnectionStatus.Recovering) }
                    is RoomEvent.ParticipantConnected -> {
                        playEventTone(4)
                        syncParticipants()
                        sendProfile()
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        playEventTone(5)
                        syncParticipants()
                    }
                    is RoomEvent.Reconnected -> {
                        _state.update { it.copy(status = ConnectionStatus.Connected, error = null) }
                        sendProfile()
                        requestProfiles()
                        syncParticipants()
                    }
                    is RoomEvent.Disconnected -> {
                        if (!userLeft && wantedRoom != null) scheduleRecovery()
                    }
                    is RoomEvent.DataReceived -> handleData(event)
                    is RoomEvent.FailedToConnect -> showFailure(event.error)
                    else -> Unit
                }
            }
        }
    }

    private fun handleData(event: RoomEvent.DataReceived) {
        if (event.topic != "mhtalk.chat") return
        val participant = event.participant ?: return
        runCatching {
            val payload = JSONObject(event.data.toString(Charsets.UTF_8))
            val identity = participant.identity?.value ?: return
            if (identity in blockedIdentities) return
            when (payload.optString("type")) {
                "profile-request" -> viewModelScope.launch { sendProfile() }
                "profile" -> {
                    val source = payload.optJSONObject("profile") ?: return
                    profiles[identity] = UserProfile(
                        name = source.optString("name", identity.take(16)).trim().take(60).ifBlank { identity.take(16) },
                        bio = source.optString("bio").trim().take(240),
                        avatar = normalizeRoomAvatar(source.optString("avatar")),
                    )
                    if (remoteTyping.containsKey(identity)) {
                        remoteTyping[identity] = profiles[identity]?.name ?: identity.take(16)
                    }
                    _state.update { current ->
                        current.copy(
                            messages = current.messages.map { message ->
                                if (message.senderIdentity == identity) {
                                    message.copy(sender = profiles[identity]?.name ?: message.sender)
                                } else message
                            },
                            typingNames = remoteTyping.values.distinct(),
                        )
                    }
                    syncParticipants()
                }
                "chat" -> {
                    val body = payload.optString("body")
                    if (body.isBlank()) return
                    val incoming = ChatMessageUi(
                        id = payload.optString("id", UUID.randomUUID().toString()),
                        sender = profiles[identity]?.name ?: identity.take(16),
                        senderIdentity = identity,
                        body = body,
                        createdAt = payload.optLong("createdAt", System.currentTimeMillis()),
                        mine = false,
                        replyToId = payload.optJSONObject("replyTo")?.optString("id"),
                        replyToSender = payload.optJSONObject("replyTo")?.optString("sender"),
                        replyToBody = payload.optJSONObject("replyTo")?.optString("body"),
                    )
                    _state.update { it.copy(messages = it.messages + incoming) }
                    playEventTone(1)
                }
                "edit" -> {
                    val id = payload.optString("id")
                    val body = payload.optString("body")
                    _state.update { current ->
                        current.copy(messages = current.messages.map { if (it.id == id) it.copy(body = body) else it })
                    }
                }
                "delete" -> {
                    val id = payload.optString("id")
                    _state.update { current ->
                        current.copy(messages = current.messages.map { if (it.id == id) it.copy(body = "", deleted = true) else it })
                    }
                }
                "typing" -> {
                    typingTimeoutJobs.remove(identity)?.cancel()
                    if (payload.optBoolean("typing")) {
                        remoteTyping[identity] = profiles[identity]?.name ?: identity.take(16)
                        typingTimeoutJobs[identity] = viewModelScope.launch {
                            delay(3_000)
                            remoteTyping.remove(identity)
                            _state.update { it.copy(typingNames = remoteTyping.values.distinct()) }
                        }
                    } else {
                        remoteTyping.remove(identity)
                    }
                    _state.update { it.copy(typingNames = remoteTyping.values.distinct()) }
                }
            }
        }
    }

    private fun syncParticipants() {
        val remote = room.remoteParticipants.values.map { participant -> participant.toUi() }
            .filterNot { it.identity in blockedIdentities }
        val previous = _state.value.members.associateBy { it.identity }
        remote.forEach { member ->
            previous[member.identity]?.let { old ->
                if (!old.cameraEnabled && member.cameraEnabled) playEventTone(2)
                if (!old.screenShareEnabled && member.screenShareEnabled) playEventTone(3)
                if (old.screenShareEnabled && !member.screenShareEnabled) playEventTone(6)
            }
        }
        _state.update {
            it.copy(
                localSpeaking = room.localParticipant.isSpeaking,
                members = remote,
            )
        }
    }

    private fun disableAutoSubscribeForRemoteMedia() {
        room.remoteParticipants.values.forEach { participant ->
            listOf(Track.Source.CAMERA, Track.Source.SCREEN_SHARE, Track.Source.SCREEN_SHARE_AUDIO).forEach { source ->
                (participant.getTrackPublication(source) as? RemoteTrackPublication)?.setSubscribed(false)
            }
        }
    }

    private fun Participant.toUi(): MemberUi {
        val id = identity?.value?.takeIf { it.isNotBlank() } ?: sid.value
        val dataProfile = profiles[id]
        val metadataProfile = participantMetadataProfile(id)
        val displayName = dataProfile?.name?.takeIf { it.isNotBlank() }
            ?: metadataProfile?.name?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: id.take(16).ifBlank { "Member" }
        val dataAvatar = normalizeRoomAvatar(dataProfile?.avatar.orEmpty())
        val metadataAvatar = normalizeRoomAvatar(metadataProfile?.avatar.orEmpty())
        val avatar = dataAvatar.takeIf(::isImageAvatar)
            ?: metadataAvatar.takeIf(::isImageAvatar)
            ?: dataAvatar.ifBlank { metadataAvatar }
        val microphone = getTrackPublication(Track.Source.MICROPHONE)
        val camera = getTrackPublication(Track.Source.CAMERA)
        val screen = getTrackPublication(Track.Source.SCREEN_SHARE)
        return MemberUi(
            identity = id,
            name = displayName,
            speaking = isSpeaking,
            microphoneEnabled = microphone != null && !microphone.muted,
            cameraEnabled = camera != null && !camera.muted,
            screenShareEnabled = screen != null && !screen.muted,
            bio = dataProfile?.bio?.ifBlank { metadataProfile?.bio.orEmpty() }
                ?: metadataProfile?.bio.orEmpty(),
            avatar = avatar,
            userVolume = userVolumes[id] ?: 100,
            streamVolume = streamVolumes[id] ?: 100,
        )
    }

    private suspend fun sendProfile() {
        if (_state.value.status == ConnectionStatus.Idle) return
        val value = profile
        val safeAvatar = normalizeRoomAvatar(value.avatar)
        val payload = JSONObject()
            .put("type", "profile")
            .put(
                "profile",
                JSONObject().put("name", value.name).put("bio", value.bio).put("avatar", safeAvatar),
            )
        runCatching {
            room.localParticipant.updateMetadata(
                JSONObject().put("name", value.name).put("bio", value.bio).put("avatar", safeAvatar).toString(),
            )
        }
        room.localParticipant.publishData(payload.toString().toByteArray(), topic = "mhtalk.chat").getOrThrow()
    }

    private fun Participant.participantMetadataProfile(identity: String): UserProfile? = runCatching {
        val source = JSONObject(metadata.orEmpty())
        val rawName = source.optString("name").ifBlank { source.optString("username") }
        val rawAvatar = source.optString("avatar").ifBlank { source.optString("avatar_url") }
        UserProfile(
            name = rawName.trim().take(60).ifBlank { identity.take(16) },
            bio = source.optString("bio").trim().take(240),
            avatar = normalizeRoomAvatar(rawAvatar),
        )
    }.getOrNull()

    private fun isImageAvatar(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) || value.startsWith("data:image/", ignoreCase = true)

    private suspend fun requestProfiles() {
        room.localParticipant.publishData(
            JSONObject().put("type", "profile-request").toString().toByteArray(),
            topic = "mhtalk.chat",
        ).getOrThrow()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun startScreenAudio() {
        if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val screenTrack = room.localParticipant.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? LocalVideoTrack ?: return
        stopScreenAudio()
        ScreenAudioCapturer.createFromScreenShareTrack(screenTrack)?.let { capturer ->
            val audioTrack = room.localParticipant.createAudioTrack(
                "MHTalk screen audio",
                LocalAudioTrackOptions(
                    noiseSuppression = false,
                    echoCancellation = false,
                    autoGainControl = false,
                    highPassFilter = false,
                    typingNoiseDetection = false,
                ),
            )
            audioTrack.setAudioBufferCallback(ScreenAudioOnlyBufferCallback(capturer))
            room.localParticipant.publishAudioTrack(
                audioTrack,
                AudioTrackPublishOptions(name = "Screen audio", source = Track.Source.SCREEN_SHARE_AUDIO),
            )
            screenAudioTrack = audioTrack
            screenAudioCapturer = capturer
        }
    }

    private fun stopScreenAudio() {
        screenAudioTrack?.let { room.localParticipant.unpublishTrack(it) }
        screenAudioTrack?.dispose()
        screenAudioTrack = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) screenAudioCapturer?.releaseAudioResources()
        screenAudioCapturer = null
    }

    private fun scheduleRecovery() {
        if (_state.value.status == ConnectionStatus.Recovering) return
        _state.update { it.copy(status = ConnectionStatus.Recovering, error = null) }
        viewModelScope.launch {
            var delayMs = 600L
            while (isActive && !userLeft && wantedRoom != null) {
                delay(delayMs)
                val name = wantedRoom ?: break
                val result = runCatching {
                    val credentials = api.credentials(name, wantedInviteCode)
                    room.connect(BuildConfig.LIVEKIT_URL, credentials.token)
                    room.localParticipant.setMicrophoneEnabled(_state.value.microphoneEnabled)
                    sendProfile()
                    credentials.roomName
                }
                if (result.isSuccess) {
                    _state.update {
                        it.copy(status = ConnectionStatus.Connected, roomName = result.getOrNull(), error = null)
                    }
                    syncParticipants()
                    break
                }
                delayMs = (delayMs * 2).coerceAtMost(8_000L)
            }
        }
    }

    private fun pollMainCount() {
        countJob?.cancel()
        countJob = viewModelScope.launch {
            while (isActive) {
                runCatching { api.mainCount() }.onSuccess { count ->
                    _state.update { it.copy(mainActiveCount = count) }
                }
                delay(15_000)
            }
        }
    }

    private fun startCallService(camera: Boolean, screenShare: Boolean) {
        val context = getApplication<Application>()
        val intent = Intent(context, CallService::class.java)
            .putExtra(CallService.EXTRA_CAMERA, camera)
            .putExtra(CallService.EXTRA_SCREEN_SHARE, screenShare)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }

    private fun showFailure(error: Throwable) {
        _state.update {
            it.copy(
                status = if (it.roomName == null) ConnectionStatus.Failed else it.status,
                error = error.message ?: "Unexpected connection error",
            )
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(taskRemovedReceiver) }
        room.disconnect()
        super.onCleared()
    }
}

private fun microphoneCaptureOptions(noiseCancellation: Boolean) = LocalAudioTrackOptions(
    noiseSuppression = noiseCancellation,
    echoCancellation = true,
    autoGainControl = true,
    highPassFilter = noiseCancellation,
    typingNoiseDetection = noiseCancellation,
)

/** Replaces the dedicated screen-audio track buffer instead of mixing the microphone into it. */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private class ScreenAudioOnlyBufferCallback(
    private val capturer: ScreenAudioCapturer,
) : AudioBufferCallback {
    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        val response = capturer.onBufferRequest(
            buffer,
            audioFormat,
            channelCount,
            sampleRate,
            bytesRead,
            captureTimeNs,
        )
        val screen = response?.byteBuffer
        for (index in 0 until buffer.capacity()) {
            buffer.put(index, if (screen != null && index < screen.capacity()) screen.get(index) else 0)
        }
        return response?.captureTimeNs ?: captureTimeNs
    }
}
