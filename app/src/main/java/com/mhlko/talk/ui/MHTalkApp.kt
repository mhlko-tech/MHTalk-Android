package com.mhlko.talk.ui

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.MediaPlayer
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.util.Rational
import android.widget.MediaController
import android.widget.VideoView
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.mhlko.talk.BuildConfig
import com.mhlko.talk.call.SessionViewModel
import com.mhlko.talk.call.AgoraRtcSession
import com.mhlko.talk.call.TencentRtcSession
import com.mhlko.talk.call.CloudflareRtcSession
import com.mhlko.talk.data.ConnectionStatus
import com.mhlko.talk.data.MemberUi
import com.mhlko.talk.data.MembershipService
import com.mhlko.talk.data.SessionUiState
import com.mhlko.talk.data.UserProfile
import com.mhlko.talk.data.isImageAvatar
import com.mhlko.talk.data.ChatMessageUi
import com.mhlko.talk.data.ShareQuality
import com.mhlko.talk.data.StartupUpdatePhase
import com.mhlko.talk.data.SubscriptionTier
import com.mhlko.talk.auth.AuthRepository
import com.mhlko.talk.auth.AuthState
import com.mhlko.talk.auth.SocialRepository
import com.mhlko.talk.ui.auth.RequiredSignInScreen
import com.mhlko.talk.ui.components.ProfileAvatar
import com.mhlko.talk.ui.theme.*
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.VideoQuality
import io.getstream.video.android.core.Call as StreamCall
import io.getstream.video.android.compose.theme.VideoTheme
import io.getstream.video.android.compose.ui.components.call.activecall.CallContent
import io.getstream.video.android.compose.ui.components.call.controls.actions.DefaultOnCallActionHandler
import io.getstream.video.android.core.call.state.LeaveCall
import io.livekit.android.renderer.SurfaceViewRenderer
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import kotlin.math.abs
import java.io.File

object PipController {
    var inPictureInPicture by mutableStateOf(false)
    var target by mutableStateOf<PipMediaTarget?>(null)
}

data class PipMediaTarget(
    val provider: String,
    val identity: String?,
    val source: String,
    val label: String,
    val liveKitTrack: VideoTrack? = null,
    val streamCall: StreamCall? = null,
)

private val embeddedRtcProviders = setOf(
    "whereby",
    "jaas",
    "mirotalk",
    "daily",
)

@Composable
fun MHTalkApp(session: SessionViewModel = viewModel()) {
    val state by session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val auth = remember(context) { AuthRepository.get(context) }
    val social = remember(context) { SocialRepository.get(context) }
    val authState by auth.state.collectAsStateWithLifecycle()
    val socialState by social.state.collectAsStateWithLifecycle()
    val appScope = rememberCoroutineScope()
    if (PipController.inPictureInPicture) {
        PipVideoScreen(PipController.target, session)
        return
    }
    var pendingShareOptions by remember { mutableStateOf<ShareOptions?>(null) }
    val updateInstaller = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* A successful install replaces this process; cancellation keeps the gate visible. */ }
    fun installDownloadedUpdate() {
        val path = state.updateApkPath ?: return
        val apk = File(path)
        if (!apk.isFile) {
            session.retryUpdateCheck()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        updateInstaller.launch(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
            installDownloadedUpdate()
        }
    }
    if (!state.launchReady) {
        LaunchScreen(
            state = state,
            onRetry = session::retryUpdateCheck,
            onInstall = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                    unknownSourcesLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                } else {
                    installDownloadedUpdate()
                }
            },
        )
        return
    }
    LaunchedEffect(authState) {
        if (authState !is AuthState.SignedIn && state.roomName != null) session.leave()
    }
    if (authState !is AuthState.SignedIn) {
        RequiredSignInScreen(
            authState = authState,
            auth = auth,
            onRetry = { appScope.launch { auth.initialize() } },
        )
        return
    }
    val subscriptionTier = (authState as AuthState.SignedIn).account.subscriptionTier
    var permissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val audioAllowed = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioAllowed) permissionAction?.invoke()
        permissionAction = null
    }
    var cameraPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        if (allowed) cameraPermissionAction?.invoke()
        cameraPermissionAction = null
    }
    val screenShareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val projectionData = result.data
        if (result.resultCode == Activity.RESULT_OK && projectionData != null) {
            pendingShareOptions?.let { options ->
                session.startScreenShare(projectionData, options.includeMicrophone, options.quality)
            }
        }
    }
    fun withCallPermission(action: () -> Unit) {
        val missing = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            permissionAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    var privateSheet by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showFriends by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    var membershipMessage by remember { mutableStateOf("") }
    var membershipSyncedAccount by remember { mutableStateOf<String?>(null) }
    var shareOptionsOpen by remember { mutableStateOf(false) }
    var pendingProfilePhoto by remember { mutableStateOf<Uri?>(null) }
    var cropSubmitted by remember { mutableStateOf(false) }
    var cropStartRevision by remember { mutableLongStateOf(0L) }
    val profilePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            pendingProfilePhoto = uri
        }
    }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(authState) {
        val signedIn = authState as? AuthState.SignedIn ?: return@LaunchedEffect
        session.saveProfile(
            UserProfile(
                name = signedIn.account.displayName,
                bio = signedIn.account.bio.orEmpty(),
                avatar = signedIn.account.avatarUrl ?: signedIn.account.displayName.take(1).uppercase(),
            ),
            syncAccount = false,
        )
        if (membershipSyncedAccount != signedIn.account.id) {
            membershipSyncedAccount = signedIn.account.id
            auth.accessToken()?.let { token ->
                runCatching { MembershipService.sync(context, token) }
                    .onSuccess { if (it != null && !it.pending) auth.refreshProfile() }
            }
        }
    }
    LaunchedEffect(state.profilePhotoRevision, cropSubmitted) {
        if (cropSubmitted && state.profilePhotoRevision > cropStartRevision) {
            pendingProfilePhoto = null
            cropSubmitted = false
        }
    }
    Scaffold(
        containerColor = MHTalkBackground,
        bottomBar = {
            if (state.roomName != null && state.rtcProvider !in embeddedRtcProviders) NavigationBar(containerColor = Color(0xFF101422)) {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Rounded.Tag, "Room") }, label = { Text("Room") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Rounded.ChatBubble, "Chat") }, label = { Text("Chat") })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Header(
                state = state,
                onEditProfile = { showProfile = true },
                onFriends = { showFriends = true; appScope.launch { social.refresh() } },
                friendRequestCount = socialState.requests.size,
                onSupport = { showSupport = true },
                onSettings = { showSettings = true },
                onHelp = { showHelp = true },
                onReport = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/m.ed1t")))
                },
            )
            when {
                state.roomName == null -> RoomsHome(
                    state,
                    onMain = { withCallPermission(session::joinMain) },
                    onPrivate = { privateSheet = true },
                )
                state.rtcProvider in embeddedRtcProviders -> EmbeddedPrebuiltRoom(
                    url = state.embeddedCallUrl,
                    onLeave = session::leave,
                )
                state.rtcProvider == "stream" && tab == 0 -> StreamNativeRoom(
                    call = session.activeStreamCall(),
                    onLeave = session::leave,
                    screenShareEnabled = state.screenShareEnabled,
                    onScreenShare = {
                        if (state.screenShareEnabled) session.stopScreenShare()
                        else shareOptionsOpen = true
                    },
                )
                tab == 0 -> ActiveRoom(
                    state,
                    session,
                    onCamera = {
                        if (state.cameraEnabled || ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            session.toggleCamera()
                        } else {
                            cameraPermissionAction = session::toggleCamera
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onScreenShare = {
                        if (state.screenShareEnabled) {
                            session.stopScreenShare()
                        } else {
                            shareOptionsOpen = true
                        }
                    },
                )
                else -> RoomChat(state, session)
            }
        }
    }

    if (state.status == ConnectionStatus.Connecting || state.status == ConnectionStatus.Recovering) {
        Dialog(onDismissRequest = {}) {
            Surface(color = MHTalkSurfaceRaised, shape = RoundedCornerShape(22.dp), tonalElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MHTalkPurple)
                    Spacer(Modifier.height(16.dp))
                    Text(if (state.status == ConnectionStatus.Recovering) "Reconnecting to the server" else "Connecting to the server", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(state.connectionMessage ?: "Please wait while MHTalk selects a compatible service.", color = MHTalkMuted, fontSize = 12.sp)
                }
            }
        }
    }

    if (privateSheet) PrivateRoomSheet(
        onDismiss = { privateSheet = false },
        onCreate = {
            privateSheet = false
            withCallPermission(session::createPrivate)
        },
        onJoin = { code ->
            privateSheet = false
            withCallPermission { session.joinPrivate(code) }
        },
    )
    if (showProfile) ProfileDialog(
        profile = state.localProfile,
        onDismiss = { showProfile = false },
        onChange = {
            session.saveProfile(it)
        },
        savingPhoto = state.profilePhotoSaving,
        onChoosePhoto = {
            profilePhotoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onRemovePhoto = session::removeProfilePhoto,
    )
    if (showFriends) FriendsDialog(
        authState = authState,
        friends = socialState.friends,
        requests = socialState.requests,
        loading = socialState.loading,
        error = socialState.error,
        onDismiss = { showFriends = false },
        onGoogle = { auth.beginSignIn("google") },
        onFacebook = { auth.beginSignIn("facebook") },
        onSignOut = { appScope.launch { auth.signOut() } },
        onSearch = social::search,
        onAdd = social::sendFriendRequest,
        onRespond = social::respond,
        onInvite = { friendId ->
            appScope.launch {
                runCatching { social.invite(friendId) }
                    .onSuccess { invite ->
                        showFriends = false
                        invite.inviteCode?.let { code -> withCallPermission { session.joinPrivate(code) } }
                    }
                    .onFailure { session.showNotice(it.message ?: "Could not invite friend") }
            }
        },
    )
    if (showSettings) SettingsDialog(
        state = state,
        subscriptionTier = subscriptionTier,
        onDismiss = { showSettings = false },
        onOutput = session::setOutputLevel,
        onTestSpeaker = session::testSpeaker,
        onSwitchCamera = session::switchCamera,
        onNoiseCancellation = session::setNoiseCancellation,
        onMessageSounds = session::setMessageSounds,
        onPresenceSounds = session::setPresenceSounds,
        onCameraSounds = session::setCameraSounds,
        onScreenSounds = session::setScreenShareSounds,
        onScreenPrivacy = session::setScreenSharePrivacy,
    )
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
    if (showSupport) SupportDialog(
        onDismiss = { showSupport = false },
        allowExternalMemberships = !BuildConfig.PLAY_DISTRIBUTION,
        onOpenLava = {
            appScope.launch {
                val accessToken = auth.accessToken()
                if (accessToken == null) {
                    membershipMessage = "Sign in before starting a membership."
                    return@launch
                }
                runCatching { MembershipService.createLavaSession(context, accessToken) }
                    .onSuccess {
                        membershipMessage = "Complete payment in your browser, then return and choose Verify membership."
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                    }
                    .onFailure { session.showNotice(it.message ?: "Could not open LAVA membership") }
            }
        },
        membershipMessage = membershipMessage,
        onVerify = {
            appScope.launch {
                val accessToken = auth.accessToken()
                if (accessToken == null) {
                    membershipMessage = "Sign in before verifying a membership."
                    return@launch
                }
                runCatching { MembershipService.sync(context, accessToken) }
                    .onSuccess { result ->
                        membershipMessage = when {
                            result == null -> "Start a LAVA membership first."
                            result.tier == SubscriptionTier.Plus -> "MHTalk Plus is active on this account."
                            result.pending -> "Payment confirmation is still pending."
                            else -> "No active LAVA membership was found."
                        }
                        if (result != null && !result.pending) auth.refreshProfile()
                    }
                    .onFailure { membershipMessage = it.message ?: "Could not verify membership" }
            }
        },
        onOpenPatreon = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.patreon.com/cw/MhlkoVD/membership"))) },
        onDownloadMvDownloader = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mhlko-tech/MVDownloader/releases/latest"))) },
        onShare = {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Try MHTalk Beta for voice, video, rooms and chat: https://github.com/mhlko-tech/MHTalk-Android/releases/latest")
            }, "Share MHTalk"))
        },
    )
    socialState.invite?.let { invite ->
        val sender = socialState.friends.firstOrNull { it.id == invite.senderId }
        AlertDialog(
            onDismissRequest = social::clearInvite,
            title = { Text("Room invitation") },
            text = { Text("${sender?.displayName ?: "A friend"} invited you to join an MHTalk room. The invitation expires after 10 minutes.") },
            confirmButton = {
                TextButton(onClick = {
                    social.clearInvite()
                    if (invite.inviteCode != null) withCallPermission { session.joinPrivate(invite.inviteCode) }
                    else withCallPermission(session::joinMain)
                }) { Text("Join") }
            },
            dismissButton = { TextButton(social::clearInvite) { Text("Not now") } },
        )
    }
    if (shareOptionsOpen) ShareOptionsDialog(
        subscriptionTier = subscriptionTier,
        onDismiss = { shareOptionsOpen = false },
        onStart = { options ->
            pendingShareOptions = options
            shareOptionsOpen = false
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = if (state.screenSharePrivacyEnabled && Build.VERSION.SDK_INT >= 34) {
                // Lets Android offer single-app sharing, where system notifications are excluded.
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
            } else manager.createScreenCaptureIntent()
            screenShareLauncher.launch(captureIntent)
        },
    )
    pendingProfilePhoto?.let { uri ->
        ProfileCropDialog(
            uri = uri,
            saving = state.profilePhotoSaving,
            onDismiss = { pendingProfilePhoto = null },
            onUse = { selection ->
                cropStartRevision = state.profilePhotoRevision
                session.chooseProfilePhoto(uri, selection)
                cropSubmitted = true
            },
        )
    }
    if (!state.termsAccepted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Welcome to MHTalk") },
            text = {
                LazyColumn(Modifier.heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text("Please accept these rules before using rooms and chat.", fontWeight = FontWeight.Bold) }
                    item { Text("Be respectful. Harassment, threats, sexual exploitation, illegal content, malware, privacy violations and copyright infringement are prohibited.", color = MHTalkMuted) }
                    item { Text("MHTalk may process voice, video, screen share, profile details, messages and files only to provide realtime communication. The selected compatible provider carries realtime media and the MHTalk service issues secure room access.", color = MHTalkMuted) }
                    item { Text("Public Main messages are filtered. You can long-press messages and open member profiles to report or block users. Reports are retained for up to 30 days for safety review.", color = MHTalkMuted) }
                    item { Text("By continuing, you agree to these Terms of Use and the Privacy Policy shown in Help.", color = MHTalkMuted) }
                }
            },
            confirmButton = { Button(session::acceptTerms) { Text("Accept and continue") } },
        )
    }
    state.privateCode?.let { code ->
        val copyPrivateCode: () -> Unit = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("MHTalk private code", code))
            session.showNotice("Code copied")
        }
        AlertDialog(
            onDismissRequest = session::clearPrivateCode,
            title = { Text("Private room created") },
            text = {
                Column {
                    Text("Send this code to your friend:", color = MHTalkMuted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            code,
                            color = MHTalkPurple,
                            fontWeight = FontWeight.Black,
                            fontSize = 24.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = copyPrivateCode),
                        )
                        IconButton(onClick = copyPrivateCode) {
                            Icon(Icons.Rounded.ContentCopy, "Copy code")
                        }
                    }
                }
            },
            confirmButton = { TextButton(session::clearPrivateCode) { Text("Done") } },
        )
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = session::dismissError,
            title = { Text("Could not connect") },
            text = { Text(error) },
            confirmButton = { TextButton(session::dismissError) { Text("OK") } },
        )
    }
    state.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = session::dismissNotice,
            title = { Text("Done") },
            text = { Text(notice) },
            confirmButton = { TextButton(session::dismissNotice) { Text("OK") } },
        )
    }
}

@Composable
private fun LaunchScreen(
    state: SessionUiState,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MHTalkBackground), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 38.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(108.dp).clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(MHTalkPurple, Color(0xFF40359D)))), contentAlignment = Alignment.Center) {
                Text("M", color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MHTalk ${BuildConfig.VERSION_NAME}", color = MHTalkText, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
                Spacer(Modifier.width(8.dp))
                BetaBadge()
            }
            Spacer(Modifier.height(13.dp))
            Text(state.launchUpdateMessage, color = MHTalkMuted, fontSize = 13.sp)
            Spacer(Modifier.height(15.dp))
            if (state.launchUpdateProgress == null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp)),
                    color = MHTalkPurple,
                    trackColor = Color(0xFF30364F),
                )
            } else {
                LinearProgressIndicator(
                    progress = { state.launchUpdateProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp)),
                    color = MHTalkPurple,
                    trackColor = Color(0xFF30364F),
                )
                Spacer(Modifier.height(7.dp))
                Text("${state.launchUpdateProgress}%", color = MHTalkMuted, fontSize = 11.sp)
            }
            when (state.launchUpdatePhase) {
                StartupUpdatePhase.ReadyToInstall -> {
                    Spacer(Modifier.height(17.dp))
                    Button(onInstall, Modifier.fillMaxWidth()) { Text("Install update") }
                }
                StartupUpdatePhase.Error -> {
                    Spacer(Modifier.height(17.dp))
                    Button(onRetry, Modifier.fillMaxWidth()) { Text("Retry") }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Header(
    state: SessionUiState,
    onEditProfile: () -> Unit,
    onFriends: () -> Unit,
    friendRequestCount: Int,
    onSupport: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onReport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MHTalkSurface).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(
            avatar = state.localProfile.avatar,
            name = state.localProfile.name,
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(14.dp),
            fontSize = 13.sp,
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            if (state.roomName == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MHTalk ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
                    Spacer(Modifier.width(7.dp))
                    BetaBadge()
                }
            } else {
                Text(if (state.roomName == "Main") "Main channel" else "Private channel", fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
            }
            Text(statusText(state.status), color = statusColor(state.status), fontSize = 12.sp)
        }
        BadgedBox(
            badge = {
                if (friendRequestCount > 0) Badge(containerColor = Color(0xFFE43B55)) {
                    Text(if (friendRequestCount > 99) "99+" else friendRequestCount.toString())
                }
            },
        ) {
            IconButton(onFriends) { Icon(Icons.Rounded.People, "Friends") }
        }
        IconButton(onSupport) {
            Icon(Icons.AutoMirrored.Rounded.HelpOutline, "Beta servers and support", tint = Color(0xFFFFD75A))
        }
        Box {
            IconButton({ menuOpen = true }) { Icon(Icons.Rounded.MoreHoriz, "Profile and settings") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Edit profile") },
                    leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    onClick = { menuOpen = false; onEditProfile() },
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                    onClick = { menuOpen = false; onSettings() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Help") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, null) },
                    onClick = { menuOpen = false; onHelp() },
                )
                DropdownMenuItem(
                    text = { Text("Report a bug") },
                    leadingIcon = { Icon(Icons.Rounded.BugReport, null) },
                    onClick = { menuOpen = false; onReport() },
                )
            }
        }
    }
}

@Composable
private fun StreamNativeRoom(
    call: StreamCall?,
    onLeave: () -> Unit,
    screenShareEnabled: Boolean,
    onScreenShare: () -> Unit,
) {
    if (call == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MHTalkPurple)
        }
        return
    }
    val activity = LocalActivity.current
    var fullScreen by remember(call) { mutableStateOf(false) }
    VideoTheme {
        Box(Modifier.fillMaxSize()) {
            StreamCallSurface(call, onLeave, Modifier.fillMaxSize())
            UnifiedMediaButtons(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                onFullScreen = { fullScreen = true },
                onPictureInPicture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ({
                    PipController.target = PipMediaTarget(
                        provider = "stream-call",
                        identity = null,
                        source = "room",
                        label = "MHTalk room",
                        streamCall = call,
                    )
                    activity?.enterPictureInPictureMode(
                        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                    )
                }) else null,
            )
            FilledTonalButton(
                onClick = onScreenShare,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 68.dp, end = 12.dp),
            ) {
                Icon(
                    if (screenShareEnabled) Icons.Rounded.StopCircle else Icons.Rounded.PresentToAll,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (screenShareEnabled) "Stop sharing" else "Share screen")
            }
        }
    }
    if (fullScreen) {
        Dialog(
            onDismissRequest = { fullScreen = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(Modifier.fillMaxSize().background(MHTalkBackground)) {
                VideoTheme { StreamCallSurface(call, onLeave, Modifier.fillMaxSize()) }
                IconButton(
                    onClick = { fullScreen = false },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(18.dp)
                        .size(48.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xD9080A14)),
                ) { Icon(Icons.Rounded.FullscreenExit, "Exit full screen", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun StreamCallSurface(call: StreamCall, onLeave: () -> Unit, modifier: Modifier) {
    CallContent(
        modifier = modifier,
        call = call,
        onBackPressed = onLeave,
        onCallAction = { action ->
            if (isStreamLeaveAction(action)) onLeave()
            else DefaultOnCallActionHandler.onCallAction(call, action)
        },
    )
}

internal fun isStreamLeaveAction(action: io.getstream.video.android.core.call.state.CallAction) =
    action is LeaveCall

@Composable
private fun EmbeddedPrebuiltRoom(url: String?, onLeave: () -> Unit) {
    val context = LocalContext.current
    val workerHost = remember { Uri.parse(BuildConfig.TOKEN_ENDPOINT).host.orEmpty().lowercase() }
    if (url.isNullOrBlank()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MHTalkPurple)
        }
        return
    }
    val webView = remember(context) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(13, 16, 28))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    post {
                        val allowed = request.resources.filter { resource ->
                            when (resource) {
                                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                else -> false
                            }
                        }
                        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host.orEmpty().lowercase()
                    if (
                        host == workerHost ||
                        host == "daily.co" || host.endsWith(".daily.co") || host.endsWith(".dailywebrtc.com") ||
                        host == "whereby.com" || host.endsWith(".whereby.com") ||
                        host == "8x8.vc" || host.endsWith(".8x8.vc") ||
                        host == "opentok.com" || host.endsWith(".opentok.com") ||
                        host == "129-159-223-64.sslip.io"
                    ) return false
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }
            }
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
    }
    Box(Modifier.fillMaxSize().background(MHTalkBackground)) {
        AndroidView(
            factory = { webView },
            update = { view -> if (view.url != url) view.loadUrl(url) },
            modifier = Modifier.fillMaxSize(),
        )
        FilledTonalIconButton(
            onClick = onLeave,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            Icon(Icons.Rounded.CallEnd, contentDescription = "Leave room")
        }
    }
}

@Composable
private fun RoomsHome(state: SessionUiState, onMain: () -> Unit, onPrivate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF171A38), MHTalkBackground))).padding(18.dp),
    ) {
        Text("ROOMS", color = MHTalkMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        RoomButton("Main channel", "${state.mainActiveCount} active", true, state.status == ConnectionStatus.Connecting, onMain)
        Spacer(Modifier.height(10.dp))
        RoomButton("Private channel", "Create or join with an invite code", false, false, onPrivate)
        Spacer(Modifier.weight(1f))
        Surface(color = Color(0xFF191E31), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Mic, null, tint = MHTalkPurple, modifier = Modifier.size(42.dp))
                Spacer(Modifier.height(14.dp))
                Text("Ready when you are", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Text("Join Main or enter a private room.", color = MHTalkMuted)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun RoomButton(title: String, subtitle: String, main: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (main) Color(0xFF34375B) else Color(0xFF20253A)),
    ) {
        Icon(if (main) Icons.Rounded.Tag else Icons.Rounded.Lock, null)
        Column(Modifier.padding(start = 13.dp).weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(subtitle, color = MHTalkMuted, fontSize = 12.sp)
        }
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else Icon(Icons.Rounded.Add, null, tint = if (main) MHTalkGreen else MHTalkPurple)
    }
}

@Composable
private fun ActiveRoom(
    state: SessionUiState,
    session: SessionViewModel,
    onCamera: () -> Unit,
    onScreenShare: () -> Unit,
) {
    var selectedMember by remember { mutableStateOf<MemberUi?>(null) }
    var memberAvatarPreview by remember { mutableStateOf<String?>(null) }
    var expandedMedia by remember { mutableStateOf<Set<String>>(emptySet()) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("IN THIS ROOM · ${state.members.size + 1}", color = MHTalkMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                MemberRow(
                    MemberUi(
                        "me",
                        state.localProfile.name,
                        state.localSpeaking,
                        state.microphoneEnabled,
                        state.cameraEnabled,
                        false,
                        state.localProfile.bio,
                        state.localProfile.avatar,
                    ),
                    true,
                    onClick = { selectedMember = it },
                )
                if (state.cameraEnabled) {
                    TextButton(
                        onClick = {
                            expandedMedia = if ("me-camera" in expandedMedia) expandedMedia - "me-camera" else expandedMedia + "me-camera"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if ("me-camera" in expandedMedia) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null)
                        Text(if ("me-camera" in expandedMedia) "Hide my camera" else "Show my camera")
                    }
                    if ("me-camera" in expandedMedia) {
                        if (state.rtcProvider == "cloudflare-realtime") {
                            CloudflareVideoTile(session, null, CloudflareRtcSession.TrackSource.Camera, "Your camera")
                        } else if (state.rtcProvider == "tencent") {
                            TencentVideoTile(session, null, TencentRtcSession.TrackSource.Camera, "Your camera")
                        } else if (state.rtcProvider == "agora") {
                            AgoraVideoTile(session, null, AgoraRtcSession.TrackSource.Camera, "Your camera")
                        } else {
                            session.videoTrack(null, Track.Source.CAMERA)?.let { VideoTile(it, session, "Your camera") }
                        }
                    }
                }
                if (state.screenShareEnabled) {
                    TextButton(
                        onClick = {
                            expandedMedia = if ("me-screen" in expandedMedia) expandedMedia - "me-screen" else expandedMedia + "me-screen"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if ("me-screen" in expandedMedia) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null)
                        Text(if ("me-screen" in expandedMedia) "Hide my stream" else "Show my stream")
                    }
                    if ("me-screen" in expandedMedia) {
                        if (state.rtcProvider == "cloudflare-realtime") {
                            CloudflareVideoTile(session, null, CloudflareRtcSession.TrackSource.Screen, "Your screen")
                        } else if (state.rtcProvider == "tencent") {
                            TencentVideoTile(session, null, TencentRtcSession.TrackSource.Screen, "Your screen")
                        } else if (state.rtcProvider == "agora") {
                            AgoraVideoTile(session, null, AgoraRtcSession.TrackSource.Screen, "Your screen")
                        } else {
                            session.videoTrack(null, Track.Source.SCREEN_SHARE)?.let {
                                VideoTile(it, session, "Your screen", isScreenShare = true)
                            }
                        }
                    }
                }
            }
            items(state.members, key = { it.identity }) { member ->
                MemberRow(member, false, onClick = { selectedMember = it })
                if (member.cameraEnabled || member.screenShareEnabled) {
                    IconButton(
                        onClick = {
                            if (member.identity in expandedMedia) {
                                session.stopWatchingMemberMedia(member.identity)
                                expandedMedia -= member.identity
                            } else {
                                session.watchMemberMedia(member.identity)
                                expandedMedia += member.identity
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Icon(if (member.identity in expandedMedia) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, "Show media", tint = MHTalkGreen) }
                    if (member.identity in expandedMedia) {
                        if (state.rtcProvider == "cloudflare-realtime") {
                            if (member.cameraEnabled) CloudflareVideoTile(session, member.identity, CloudflareRtcSession.TrackSource.Camera, "${member.name}'s camera")
                            if (member.screenShareEnabled) CloudflareVideoTile(session, member.identity, CloudflareRtcSession.TrackSource.Screen, "${member.name}'s screen")
                        } else if (state.rtcProvider == "tencent") {
                            if (member.cameraEnabled) TencentVideoTile(session, member.identity, TencentRtcSession.TrackSource.Camera, "${member.name}'s camera")
                            if (member.screenShareEnabled) TencentVideoTile(session, member.identity, TencentRtcSession.TrackSource.Screen, "${member.name}'s screen")
                        } else if (state.rtcProvider == "agora") {
                            if (member.cameraEnabled) AgoraVideoTile(session, member.identity, AgoraRtcSession.TrackSource.Camera, "${member.name}'s camera")
                            if (member.screenShareEnabled) AgoraVideoTile(session, member.identity, AgoraRtcSession.TrackSource.Screen, "${member.name}'s screen")
                        } else {
                            if (member.cameraEnabled) session.videoTrack(member.identity, Track.Source.CAMERA)?.let { VideoTile(it, session, "${member.name}'s camera") }
                            if (member.screenShareEnabled) session.videoTrack(member.identity, Track.Source.SCREEN_SHARE)?.let {
                                VideoTile(it, session, "${member.name}'s screen", isScreenShare = true, member = member)
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF111522)).padding(12.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Control(if (state.microphoneEnabled) Icons.Rounded.Mic else Icons.Rounded.MicOff, state.microphoneEnabled, false, session::toggleMicrophone, "Microphone")
            Control(if (state.cameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, state.cameraEnabled, false, onCamera, "Camera")
            Control(Icons.Rounded.PresentToAll, state.screenShareEnabled, false, onScreenShare, "Share screen")
            Control(Icons.Rounded.CallEnd, false, true, session::leave, "Leave")
        }
    }
    selectedMember?.let { member ->
        AlertDialog(
            onDismissRequest = { selectedMember = null },
            title = { Text(member.name) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    ProfileAvatar(
                        avatar = member.avatar,
                        name = member.name,
                        modifier = Modifier.size(110.dp).clickable(enabled = isImageAvatar(member.avatar)) {
                            memberAvatarPreview = member.avatar
                        },
                        shape = CircleShape,
                        fontSize = 38.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(member.bio.ifBlank { "No bio yet." }, color = MHTalkMuted)
                    if (member.identity != "me") {
                        Spacer(Modifier.height(20.dp))
                        Text("User volume · ${member.userVolume}%", modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
                        Slider(
                            value = member.userVolume.toFloat(),
                            onValueChange = { value ->
                                val volume = value.toInt()
                                session.setParticipantVolume(member.identity, stream = false, volume)
                                selectedMember = member.copy(userVolume = volume)
                            },
                            valueRange = 0f..100f,
                        )
                        if (member.screenShareEnabled) {
                            Text("Stream volume · ${member.streamVolume}%", modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
                            Slider(
                                value = member.streamVolume.toFloat(),
                                onValueChange = { value ->
                                    val volume = value.toInt()
                                    session.setParticipantVolume(member.identity, stream = true, volume)
                                    selectedMember = member.copy(streamVolume = volume)
                                },
                                valueRange = 0f..100f,
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        TextButton(
                            onClick = { session.reportUser(member.identity); selectedMember = null },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Report user") }
                        TextButton(
                            onClick = { session.blockUser(member.identity); selectedMember = null },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Block user", color = Color(0xFFFF7A8D)) }
                    }
                }
            },
            confirmButton = { TextButton({ selectedMember = null }) { Text("Close") } },
        )
    }
    memberAvatarPreview?.let { avatar ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { memberAvatarPreview = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable { memberAvatarPreview = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = avatar,
                    contentDescription = "Profile photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(18.dp).clickable(enabled = false) {},
                )
                IconButton(
                    onClick = { memberAvatarPreview = null },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(18.dp)
                        .size(48.dp).clip(CircleShape).background(Color(0xB8000000)),
                ) { Icon(Icons.Rounded.Close, "Close profile photo") }
            }
        }
    }
}

private data class ShareOptions(val includeMicrophone: Boolean, val quality: ShareQuality)

@Composable
private fun ShareOptionsDialog(
    subscriptionTier: SubscriptionTier,
    onDismiss: () -> Unit,
    onStart: (ShareOptions) -> Unit,
) {
    var includeMic by remember { mutableStateOf(true) }
    var quality by remember { mutableStateOf(ShareQuality.Medium) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start screen share") },
        text = {
            Column {
                Text("Audio", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().clickable { includeMic = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(includeMic, { includeMic = true })
                    Text("Share with microphone")
                }
                Row(Modifier.fillMaxWidth().clickable { includeMic = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!includeMic, { includeMic = false })
                    Text("Share without microphone")
                }
                Spacer(Modifier.height(12.dp))
                Text("Video quality", fontWeight = FontWeight.Bold)
                ShareQuality.entries.forEach { option ->
                    val enabled = option != ShareQuality.High || subscriptionTier == SubscriptionTier.Plus
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = enabled) { quality = option },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(quality == option, { quality = option }, enabled = enabled)
                        Text(if (option == ShareQuality.High && !enabled) "High · 1080p · Plus" else option.name)
                    }
                }
            }
        },
        confirmButton = { TextButton({ onStart(ShareOptions(includeMic, quality)) }) { Text("Continue") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CloudflareVideoTile(
    session: SessionViewModel,
    identity: String?,
    source: CloudflareRtcSession.TrackSource,
    label: String,
) {
    val mediaSource = if (source == CloudflareRtcSession.TrackSource.Screen) "screen" else "camera"
    ProviderVideoTile("cloudflare-realtime", identity, mediaSource, label) { modifier ->
        CloudflareVideoSurface(session, identity, source, modifier)
    }
}

@Composable
private fun CloudflareVideoSurface(
    session: SessionViewModel,
    identity: String?,
    source: CloudflareRtcSession.TrackSource,
    modifier: Modifier,
) {
    var track by remember(identity, source) { mutableStateOf(session.cloudflareVideoTrack(identity, source)) }
    LaunchedEffect(identity, source) {
        repeat(80) {
            track = session.cloudflareVideoTrack(identity, source)
            if (track != null) return@LaunchedEffect
            delay(100)
        }
    }
    track?.let { activeTrack ->
        AndroidView(
            factory = { context ->
                org.webrtc.SurfaceViewRenderer(context).also { renderer ->
                    session.initializeCloudflareRenderer(renderer)
                    renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    renderer.setMirror(identity == null && source == CloudflareRtcSession.TrackSource.Camera)
                    activeTrack.addSink(renderer)
                }
            },
            onRelease = { renderer ->
                activeTrack.removeSink(renderer)
                renderer.release()
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun AgoraVideoTile(
    session: SessionViewModel,
    identity: String?,
    source: AgoraRtcSession.TrackSource,
    label: String,
) {
    val mediaSource = if (source == AgoraRtcSession.TrackSource.Screen) "screen" else "camera"
    ProviderVideoTile("agora", identity, mediaSource, label) { modifier ->
        AndroidView(
            factory = { context ->
                session.agoraVideoView(context, identity, source)
                    ?: android.widget.FrameLayout(context)
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun TencentVideoTile(
    session: SessionViewModel,
    identity: String?,
    source: TencentRtcSession.TrackSource,
    label: String,
) {
    val mediaSource = if (source == TencentRtcSession.TrackSource.Screen) "screen" else "camera"
    ProviderVideoTile("tencent", identity, mediaSource, label) { modifier ->
        AndroidView(
            factory = { context ->
                session.tencentVideoView(context, identity, source)
                    ?: android.widget.FrameLayout(context)
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun ProviderVideoTile(
    provider: String,
    identity: String?,
    source: String,
    label: String,
    renderer: @Composable (Modifier) -> Unit,
) {
    val activity = LocalActivity.current
    var fullScreen by remember(provider, identity, source) { mutableStateOf(false) }
    val aspectRatio = 16f / 9f
    val target = PipMediaTarget(provider, identity, source, label)

    Surface(
        modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio),
        color = Color.Black,
        shape = RoundedCornerShape(18.dp),
    ) {
        Box {
            if (!fullScreen) renderer(Modifier.fillMaxSize())
            Text(
                label,
                modifier = Modifier.align(Alignment.BottomStart)
                    .background(Color(0xB8000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = Color.White,
                fontSize = 12.sp,
            )
            UnifiedMediaButtons(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                onFullScreen = { fullScreen = true },
                onPictureInPicture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ({
                    PipController.target = target
                    activity?.enterPictureInPictureMode(
                        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                    )
                }) else null,
            )
        }
    }

    if (fullScreen) {
        FullScreenMediaDialog(
            aspectRatio = aspectRatio,
            onDismiss = { fullScreen = false },
            renderer = renderer,
        )
    }
}

@Composable
private fun UnifiedMediaButtons(
    modifier: Modifier = Modifier,
    onFullScreen: () -> Unit,
    onPictureInPicture: (() -> Unit)?,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        IconButton(
            onClick = onFullScreen,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xD9080A14)),
        ) { Icon(Icons.Rounded.Fullscreen, "Full screen", tint = Color.White) }
        if (onPictureInPicture != null) {
            IconButton(
                onClick = onPictureInPicture,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xD9080A14)),
            ) { Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
        }
    }
}

@Composable
private fun FullScreenMediaDialog(
    aspectRatio: Float,
    onDismiss: () -> Unit,
    renderer: @Composable (Modifier) -> Unit,
) {
    val activity = LocalActivity.current
    val configuration = LocalConfiguration.current
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val viewportRatio = with(LocalDensity.current) { maxWidth.toPx() / maxHeight.toPx().coerceAtLeast(1f) }
                val fitModifier = if (aspectRatio >= viewportRatio) {
                    Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                } else {
                    Modifier.fillMaxHeight().aspectRatio(aspectRatio)
                }
                renderer(fitModifier)
            }
            Row(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = {
                        activity?.requestedOrientation = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    },
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xD9080A14)),
                ) { Icon(Icons.Rounded.ScreenRotation, "Rotate", tint = Color.White) }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xD9080A14)),
                ) { Icon(Icons.Rounded.FullscreenExit, "Exit full screen", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun VideoTile(
    track: VideoTrack,
    session: SessionViewModel,
    label: String,
    isScreenShare: Boolean = false,
    member: MemberUi? = null,
) {
    val activity = LocalActivity.current
    var controlsVisible by remember(track) { mutableStateOf(true) }
    var fullScreen by remember(track) { mutableStateOf(false) }
    var soundMenuOpen by remember(track) { mutableStateOf(false) }
    var qualityMenuOpen by remember(track) { mutableStateOf(false) }
    var userVolume by remember(member?.identity) { mutableIntStateOf(member?.userVolume ?: 100) }
    var streamVolume by remember(member?.identity) { mutableIntStateOf(member?.streamVolume ?: 100) }
    var aspectRatio by remember(track) { mutableFloatStateOf(16f / 9f) }

    // Remote publications update their dimensions whenever Android rotates the shared display.
    // Sampling this small piece of metadata keeps the Compose container in sync as well.
    LaunchedEffect(track, member?.identity, isScreenShare) {
        while (true) {
            session.videoAspectRatio(member?.identity, if (isScreenShare) Track.Source.SCREEN_SHARE else Track.Source.CAMERA)
                ?.let { aspectRatio = it }
            delay(350)
        }
    }

    Surface(
        // Screen capture can switch between portrait and landscape while it is live.
        // The renderer reports every frame-size/rotation change, so never force it into 16:9.
        modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio.coerceIn(0.35f, 3f)),
        color = Color.Black,
        shape = RoundedCornerShape(18.dp),
    ) {
        Box {
            AdaptiveVideoRenderer(
                track = track,
                session = session,
                modifier = Modifier.fillMaxSize(),
                mirror = label == "Your camera",
                onAspectRatio = { aspectRatio = it },
            )
            Box(
                Modifier.fillMaxSize().clickable { controlsVisible = !controlsVisible },
            ) {
                if (controlsVisible) {
                    Text(
                        label,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
                if (controlsVisible) {
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (member != null) {
                            Box {
                                IconButton(
                                    onClick = { soundMenuOpen = true },
                                    modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xB8000000)),
                                ) { Icon(Icons.Rounded.MoreVert, "Audio controls") }
                                DropdownMenu(soundMenuOpen, { soundMenuOpen = false }) {
                                    Column(Modifier.width(260.dp).padding(16.dp)) {
                                        Text("Audio controls", fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(10.dp))
                                        Text("${member.name}'s voice · $userVolume%", color = MHTalkMuted, fontSize = 12.sp)
                                        Slider(
                                            value = userVolume.toFloat(),
                                            onValueChange = { value ->
                                                userVolume = value.toInt()
                                                session.setParticipantVolume(member.identity, stream = false, userVolume)
                                            },
                                            valueRange = 0f..100f,
                                        )
                                        Text("Screen-share sound · $streamVolume%", color = MHTalkMuted, fontSize = 12.sp)
                                        Slider(
                                            value = streamVolume.toFloat(),
                                            onValueChange = { value ->
                                                streamVolume = value.toInt()
                                                session.setParticipantVolume(member.identity, stream = true, streamVolume)
                                            },
                                            valueRange = 0f..100f,
                                        )
                                    }
                                }
                            }
                            Box {
                                IconButton(
                                    onClick = { qualityMenuOpen = true },
                                    modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xB8000000)),
                                ) { Icon(Icons.Rounded.HighQuality, "Stream quality") }
                                DropdownMenu(qualityMenuOpen, { qualityMenuOpen = false }) {
                                    listOf(VideoQuality.LOW, VideoQuality.MEDIUM, VideoQuality.HIGH).forEach { quality ->
                                        DropdownMenuItem(
                                            text = { Text("${quality.name.lowercase().replaceFirstChar { it.uppercase() }} quality") },
                                            onClick = {
                                                session.setMemberVideoQuality(member.identity, Track.Source.SCREEN_SHARE, quality)
                                                qualityMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        UnifiedMediaButtons(
                            onFullScreen = { fullScreen = true },
                            onPictureInPicture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ({
                                PipController.target = PipMediaTarget(
                                    provider = "livekit-track",
                                    identity = member?.identity,
                                    source = if (isScreenShare) "screen" else "camera",
                                    label = label,
                                    liveKitTrack = track,
                                )
                                activity?.enterPictureInPictureMode(
                                    PictureInPictureParams.Builder()
                                        .setAspectRatio(Rational((aspectRatio * 1_000).toInt().coerceAtLeast(1), 1_000))
                                        .build(),
                                )
                            }) else null,
                        )
                    }
                }
            }
        }
    }

    if (fullScreen) {
        val activity = LocalActivity.current
        val configuration = LocalConfiguration.current
        DisposableEffect(activity) {
            onDispose {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullScreen = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            var fullControlsVisible by remember { mutableStateOf(true) }
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable { fullControlsVisible = !fullControlsVisible },
                contentAlignment = Alignment.Center,
            ) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val viewportRatio = with(LocalDensity.current) {
                        maxWidth.toPx() / maxHeight.toPx().coerceAtLeast(1f)
                    }
                    val fitModifier = if (aspectRatio >= viewportRatio) {
                        Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                    } else {
                        Modifier.fillMaxHeight().aspectRatio(aspectRatio)
                    }
                    AdaptiveVideoRenderer(
                        track = track,
                        session = session,
                        modifier = fitModifier,
                        onAspectRatio = { aspectRatio = it },
                    )
                }
                if (fullControlsVisible) {
                    Row(
                        Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(
                            onClick = {
                                activity?.requestedOrientation = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                }
                            },
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xB8000000)),
                        ) { Icon(Icons.Rounded.ScreenRotation, "Rotate") }
                        IconButton(
                            onClick = { fullScreen = false },
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xB8000000)),
                        ) { Icon(Icons.Rounded.FullscreenExit, "Exit full screen") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipVideoScreen(target: PipMediaTarget?, session: SessionViewModel) {
    val activity = LocalActivity.current
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when (target?.provider) {
            "stream-call" -> target.streamCall?.let { call ->
                VideoTheme {
                    StreamCallSurface(call, {}, Modifier.fillMaxSize())
                }
            }
            "livekit-track" -> target.liveKitTrack?.let { videoTrack ->
                AdaptiveVideoRenderer(
                    track = videoTrack,
                    session = session,
                    modifier = Modifier.fillMaxSize(),
                    onAspectRatio = { ratio ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            activity?.setPictureInPictureParams(
                                PictureInPictureParams.Builder()
                                    .setAspectRatio(Rational((ratio * 1_000).toInt().coerceAtLeast(1), 1_000))
                                    .build(),
                            )
                        }
                    },
                )
            }
            "cloudflare-realtime" -> CloudflareVideoSurface(
                session,
                target.identity,
                if (target.source == "screen") CloudflareRtcSession.TrackSource.Screen else CloudflareRtcSession.TrackSource.Camera,
                Modifier.fillMaxSize(),
            )
            "agora" -> AndroidView(
                factory = { viewContext ->
                    session.agoraVideoView(
                        viewContext,
                        target.identity,
                        if (target.source == "screen") AgoraRtcSession.TrackSource.Screen else AgoraRtcSession.TrackSource.Camera,
                    ) ?: android.widget.FrameLayout(viewContext)
                },
                modifier = Modifier.fillMaxSize(),
            )
            "tencent" -> AndroidView(
                factory = { viewContext ->
                    session.tencentVideoView(
                        viewContext,
                        target.identity,
                        if (target.source == "screen") TencentRtcSession.TrackSource.Screen else TencentRtcSession.TrackSource.Camera,
                    ) ?: android.widget.FrameLayout(viewContext)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}


/**
 * LiveKit's publication dimensions can lag behind a phone rotation. This sink measures every
 * decoded WebRTC frame instead, including the frame rotation, and forwards it to LiveKit's
 * renderer. Compose can therefore reshape the tile as soon as the sender rotates the device.
 */
private class AdaptiveVideoSink(
    private val onAspectRatio: (Float) -> Unit,
) : VideoSink {
    @Volatile
    var renderer: SurfaceViewRenderer? = null

    private var lastAspectRatio = 0f

    override fun onFrame(frame: VideoFrame) {
        renderer?.onFrame(frame)
        val width = frame.rotatedWidth
        val height = frame.rotatedHeight
        if (width <= 0 || height <= 0) return

        val ratio = width.toFloat() / height.toFloat()
        if (abs(ratio - lastAspectRatio) >= 0.01f) {
            lastAspectRatio = ratio
            renderer?.post { onAspectRatio(ratio.coerceIn(0.35f, 3f)) }
        }
    }
}

@Composable
private fun AdaptiveVideoRenderer(
    track: VideoTrack,
    session: SessionViewModel,
    modifier: Modifier,
    mirror: Boolean = false,
    onAspectRatio: (Float) -> Unit = {},
) {
    val currentAspectCallback by rememberUpdatedState(onAspectRatio)
    val sink = remember(track) { AdaptiveVideoSink { currentAspectCallback(it) } }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            SurfaceViewRenderer(viewContext).also { renderer ->
                session.initializeVideoRenderer(renderer)
                renderer.setMirror(mirror)
                renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                sink.renderer = renderer
                track.addRenderer(sink)
            }
        },
        update = { renderer ->
            renderer.setMirror(mirror)
            sink.renderer = renderer
        },
        onRelease = { renderer ->
            track.removeRenderer(sink)
            if (sink.renderer === renderer) sink.renderer = null
            renderer.release()
        },
    )
}

@Composable
private fun MemberRow(member: MemberUi, mine: Boolean, onClick: (MemberUi) -> Unit) {
    Surface(
        onClick = { onClick(member) },
        color = Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        border = if (member.speaking) androidx.compose.foundation.BorderStroke(1.5.dp, MHTalkGreen) else null,
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(
                avatar = member.avatar,
                name = member.name,
                modifier = Modifier.size(43.dp),
                shape = CircleShape,
                background = if (mine) MHTalkPurple else Color(0xFF343A59),
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(member.name, fontWeight = FontWeight.Bold)
                Text(if (member.microphoneEnabled) "Mic on" else "Listening", color = MHTalkMuted, fontSize = 12.sp)
            }
            if (member.cameraEnabled) Icon(Icons.Rounded.Videocam, "Camera on", tint = MHTalkGreen)
            if (member.screenShareEnabled) Icon(Icons.Rounded.PresentToAll, "Screen sharing", tint = MHTalkGreen, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun Control(icon: ImageVector, active: Boolean, danger: Boolean, onClick: () -> Unit, description: String) {
    IconButton(
        onClick, modifier = Modifier.size(54.dp).clip(CircleShape).background(
            when { danger -> Color(0xFF7A3045); active -> Color(0xFF245F4D); else -> Color(0xFF2B3049) },
        ),
    ) { Icon(icon, description) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomChat(state: SessionUiState, session: SessionViewModel) {
    var text by remember { mutableStateOf("") }
    var imagePreview by remember { mutableStateOf<String?>(null) }
    var videoPreview by remember { mutableStateOf<String?>(null) }
    var selectedMessage by remember { mutableStateOf<ChatMessageUi?>(null) }
    var replyTo by remember { mutableStateOf<ChatMessageUi?>(null) }
    var editing by remember { mutableStateOf<ChatMessageUi?>(null) }
    var newMessageLabel by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) session.sendAttachment(uri)
    }
    val submitMessage = {
        if (text.isNotBlank()) {
            session.updateTyping(false)
            editing?.let { session.editMessage(it.id, text) } ?: session.sendMessage(text, replyTo)
            text = ""
            editing = null
            replyTo = null
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isEmpty()) {
            newMessageLabel = null
        } else {
            val newLast = state.messages.lastIndex
            val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (newLast == 0 || visibleLast >= newLast - 1) {
                listState.animateScrollToItem(newLast)
                newMessageLabel = null
            } else {
                newMessageLabel = if (state.messages.last().mine) "Your last message" else "New message"
            }
        }
    }
    Column(Modifier.fillMaxSize().then(if (imagePreview != null || videoPreview != null) Modifier.blur(8.dp) else Modifier)) {
        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            if (state.messages.isEmpty()) item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("Messages appear here.", color = MHTalkMuted) } }
            items(state.messages, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (message.mine) Color(0xFF5749A8) else Color(0xFF20253A),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(0.82f).combinedClickable(
                            onClick = {},
                            onLongClick = { if (!message.deleted) selectedMessage = message },
                        ),
                    ) {
                        Column(Modifier.padding(11.dp)) {
                            Text(message.sender, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            if (!message.replyToId.isNullOrBlank()) {
                                Surface(
                                    color = Color(0x44202020),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        val index = state.messages.indexOfFirst { it.id == message.replyToId }
                                        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                                    },
                                ) {
                                    Column(Modifier.padding(7.dp)) {
                                        Text(message.replyToSender.orEmpty(), color = MHTalkPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(message.replyToBody.orEmpty(), maxLines = 2, color = MHTalkMuted, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(5.dp))
                            }
                            if (message.deleted) {
                                Text("Message deleted", color = MHTalkMuted)
                            } else {
                                message.attachment?.let { attachment ->
                                    if (attachment.mimeType.startsWith("image/")) {
                                        AsyncImage(
                                            model = attachment.uri,
                                            contentDescription = attachment.name,
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(
                                                onClick = { imagePreview = attachment.uri },
                                                onLongClick = { selectedMessage = message },
                                            ),
                                        )
                                    } else if (attachment.mimeType.startsWith("audio/")) {
                                        VoiceAttachment(attachment.uri, attachment.name)
                                    } else if (attachment.mimeType.startsWith("video/")) {
                                        VideoAttachment(attachment.uri, attachment.name, onPreview = { videoPreview = attachment.uri }, onLongClick = { selectedMessage = message })
                                    } else {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                openAttachment(context, attachment.uri, attachment.mimeType)
                                            },
                                        ) {
                                            Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = MHTalkPurple)
                                            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                                                Text(attachment.name, maxLines = 2)
                                                Text(formatBytes(attachment.size), color = MHTalkMuted, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    if (attachment.sending) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            LinearProgressIndicator(
                                                progress = { attachment.progress },
                                                modifier = Modifier.weight(1f).padding(top = 7.dp),
                                            )
                                            IconButton({ session.cancelAttachment(message.id) }, Modifier.size(34.dp)) {
                                                Icon(Icons.Rounded.Close, "Cancel sending")
                                            }
                                        }
                                    }
                                }
                                if (message.body.isNotBlank()) Text(message.body, color = Color.White)
                            }
                        }
                    }
                }
            }
            }
            newMessageLabel?.let { label ->
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(state.messages.lastIndex)
                            newMessageLabel = null
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null)
                    Spacer(Modifier.width(5.dp))
                    Text(label)
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF111522))) {
            if (state.typingNames.isNotEmpty()) {
                Text(
                    "${state.typingNames.joinToString()} ${if (state.typingNames.size == 1) "is" else "are"} typing…",
                    color = MHTalkMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                )
            }
            (editing ?: replyTo)?.let { target ->
                Row(Modifier.fillMaxWidth().background(Color(0xFF20253A)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (editing != null) "Editing message" else "Replying to ${target.sender}", color = MHTalkPurple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(target.body, maxLines = 1, color = MHTalkMuted, fontSize = 11.sp)
                    }
                    IconButton(onClick = { editing = null; replyTo = null; text = "" }) { Icon(Icons.Rounded.Close, "Cancel") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { attachmentPicker.launch(arrayOf("image/*", "video/*", "audio/*", "application/*", "text/*")) },
                modifier = Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF2B3049)),
            ) { Icon(Icons.Rounded.Add, "Attach file") }
            Spacer(Modifier.width(7.dp))
            OutlinedTextField(
                text,
                {
                    text = it.take(8_000)
                    session.updateTyping(text.isNotBlank())
                },
                Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text("Write a message") },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submitMessage() }),
                shape = RoundedCornerShape(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (text.isBlank() && editing == null && replyTo == null) {
                IconButton(
                    onClick = session::toggleVoiceRecording,
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(if (state.isRecordingVoice) Color(0xFFB33951) else MHTalkPurple),
                ) { Icon(if (state.isRecordingVoice) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord, if (state.isRecordingVoice) "Stop voice note" else "Record voice note") }
            } else {
                IconButton(
                    onClick = submitMessage,
                    enabled = text.isNotBlank(),
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(MHTalkPurple),
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
            }
            }
        }
    }
    imagePreview?.let { uri ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { imagePreview = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)).clickable { imagePreview = null }.padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = "Image preview",
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f).clickable(enabled = false) {},
                )
            }
        }
    }
    videoPreview?.let { uri ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { videoPreview = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { videoPreview = null }, contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { viewContext ->
                        VideoView(viewContext).apply {
                            setVideoURI(Uri.parse(uri))
                            setMediaController(MediaController(viewContext).also { it.setAnchorView(this) })
                            setOnPreparedListener { it.start() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f).clickable(enabled = false) {},
                )
            }
        }
    }
    selectedMessage?.let { message ->
        MessageActionsSheet(
            message = message,
            onDismiss = { selectedMessage = null },
            onReply = {
                replyTo = message
                selectedMessage = null
                scope.launch { focusRequester.requestFocus() }
            },
            onCopy = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("MHTalk message", message.body))
                selectedMessage = null
            },
            onEdit = {
                editing = message
                text = message.body
                selectedMessage = null
                scope.launch { focusRequester.requestFocus() }
            },
            onDelete = { session.deleteMessage(message.id); selectedMessage = null },
            onReport = { message.senderIdentity?.let { session.reportUser(it, message) }; selectedMessage = null },
            onBlock = { message.senderIdentity?.let { session.blockUser(it) }; selectedMessage = null },
            onDownload = { message.attachment?.let(session::saveAttachmentToDownloads); selectedMessage = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    message: ChatMessageUi,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    onDownload: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MHTalkSurfaceRaised,
        contentColor = MHTalkText,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
            Surface(
                color = if (message.mine) Color(0xFF5749A8) else MHTalkSurface,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(message.sender, color = MHTalkPurple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(message.body.ifBlank { message.attachment?.name ?: "Attachment" }, maxLines = 3, color = MHTalkText)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MessageAction(Icons.AutoMirrored.Rounded.Reply, "Reply", onReply)
                MessageAction(Icons.Rounded.ContentCopy, "Copy", onCopy)
                if (message.mine && message.attachment == null) MessageAction(Icons.Rounded.Edit, "Edit", onEdit)
                if (message.mine) MessageAction(Icons.Rounded.Delete, "Delete", onDelete, destructive = true)
                if (message.attachment != null) MessageAction(Icons.Rounded.Download, "Download", onDownload)
                if (!message.mine && !message.senderIdentity.isNullOrBlank()) {
                    MessageAction(Icons.Rounded.Flag, "Report", onReport, destructive = true)
                    MessageAction(Icons.Rounded.Block, "Block", onBlock, destructive = true)
                }
            }
        }
    }
}

@Composable
private fun MessageAction(icon: ImageVector, label: String, onClick: () -> Unit, destructive: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(4.dp)) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (destructive) Color(0xFF60313F) else Color(0xFF303751)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (destructive) Color(0xFFFFA4B2) else MHTalkText) }
        Spacer(Modifier.height(5.dp))
        Text(label, color = if (destructive) Color(0xFFFFA4B2) else MHTalkMuted, fontSize = 11.sp)
    }
}

@Composable
private fun VideoAttachment(uri: String, name: String, onPreview: () -> Unit, onLongClick: () -> Unit) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                val controls = MediaController(context)
                controls.setAnchorView(this)
                setMediaController(controls)
                setVideoURI(Uri.parse(uri))
                setOnPreparedListener { player ->
                    player.isLooping = false
                    seekTo(1)
                }
                setOnClickListener { onPreview() }
                setOnLongClickListener { onLongClick(); true }
            }
        },
        update = { view ->
            if (view.tag != uri) {
                view.tag = uri
                view.setVideoURI(Uri.parse(uri))
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(12.dp)),
    )
    Text(name, maxLines = 1, color = MHTalkMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
}

private fun openAttachment(context: Context, uri: String, mimeType: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

@Composable
private fun VoiceAttachment(uri: String, name: String) {
    val context = LocalContext.current
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember(uri) { mutableStateOf(false) }
    var playing by remember(uri) { mutableStateOf(false) }
    var duration by remember(uri) { mutableIntStateOf(0) }
    var position by remember(uri) { mutableIntStateOf(0) }

    DisposableEffect(uri) {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        runCatching {
            mediaPlayer.setDataSource(context, Uri.parse(uri))
            mediaPlayer.setOnPreparedListener {
                duration = it.duration.coerceAtLeast(0)
                ready = true
            }
            mediaPlayer.setOnCompletionListener {
                playing = false
                position = 0
                it.seekTo(0)
            }
            mediaPlayer.prepareAsync()
        }.onFailure {
            ready = false
        }
        onDispose {
            runCatching { mediaPlayer.release() }
            player = null
        }
    }
    LaunchedEffect(playing, player) {
        while (playing) {
            position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(150)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    player?.let {
                        if (playing) it.pause() else it.start()
                        playing = !playing
                    }
                },
                enabled = ready,
                modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.White),
            ) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, name, tint = Color(0xFF272342))
            }
            Slider(
                value = position.toFloat(),
                onValueChange = {
                    position = it.toInt()
                    player?.seekTo(position)
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = ready,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("${formatDuration(position)} / ${formatDuration(duration)}", fontSize = 11.sp, color = MHTalkMuted)
        }
        Row(
            Modifier.fillMaxWidth().height(22.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(9, 14, 20, 11, 17, 23, 13, 19, 10, 16, 22, 12, 18, 8, 15, 21, 11, 17).forEach { height ->
                Box(Modifier.width(3.dp).height(height.dp).clip(CircleShape).background(Color(0xFFC7BFFF)))
            }
        }
    }
}

private fun formatDuration(milliseconds: Int): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1_000)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private val emojis = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😍", "🥰", "😘",
    "😎", "🤩", "🥳", "🙂", "🙃", "😉", "😌", "😋", "😜", "🤔", "🫡", "🤗",
    "😭", "😢", "😡", "🤬", "😱", "😴", "🥺", "😏", "🙄", "😬", "🤯", "🫠",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "💕", "💯", "🔥",
    "👍", "👎", "👏", "🙌", "🙏", "🤝", "💪", "👌", "✌️", "🤞", "👀", "🫶",
    "✅", "❌", "⚠️", "🎉", "🎮", "🎧", "🎤", "📷", "💻", "📱", "🚀", "✨",
)

private fun formatBytes(size: Long): String = when {
    size < 0 -> "File"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> "${size / (1024 * 1024)} MB"
}




@Composable
private fun SupportDialog(
    onDismiss: () -> Unit,
    allowExternalMemberships: Boolean,
    onOpenLava: () -> Unit,
    membershipMessage: String,
    onVerify: () -> Unit,
    onOpenPatreon: () -> Unit,
    onDownloadMvDownloader: () -> Unit,
    onShare: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFF4C401B), shape = RoundedCornerShape(12.dp)) {
                    Text("?", color = Color(0xFFFFDC67), fontWeight = FontWeight.Black, fontSize = 23.sp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column { Text("MHTalk Beta", fontWeight = FontWeight.Bold); Text("Zero-budget public testing", color = MHTalkMuted, fontSize = 11.sp) }
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("MHTalk Beta selects among compatible free realtime providers. A provider is used only when its server route and this app version are ready.", color = MHTalkMuted) }
                item { Text("The server selects a provider before the room opens and retries the next compatible provider if room creation fails. Active rooms are never moved between incompatible providers.", color = MHTalkMuted) }
                item {
                    Surface(color = Color(0xFF222944), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("You can help without paying.", fontWeight = FontWeight.Bold)
                            Text("Sharing MHTalk is one of the most useful ways to help this small project reach sustainable hosting.", color = MHTalkMuted, fontSize = 12.sp)
                        }
                    }
                }
                if (allowExternalMemberships) item { Text("One active membership is planned to unlock premium features in both MHTalk and MVDownloader.", color = MHTalkMuted, fontSize = 12.sp) }
                if (allowExternalMemberships && membershipMessage.isNotBlank()) item {
                    Surface(color = Color(0xFF172C26), shape = RoundedCornerShape(10.dp)) {
                        Text(membershipMessage, color = MHTalkGreen, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                    }
                }
                if (allowExternalMemberships) {
                    item { Button(onOpenLava, Modifier.fillMaxWidth()) { Text("Support with LAVA") } }
                    item { OutlinedButton(onVerify, Modifier.fillMaxWidth()) { Text("Verify membership") } }
                    item { OutlinedButton(onOpenPatreon, Modifier.fillMaxWidth()) { Text("View Patreon plans") } }
                } else {
                    item { Text("MHTalk Plus purchases are not offered in this Google Play build.", color = MHTalkMuted, fontSize = 12.sp) }
                }
                item { OutlinedButton(onDownloadMvDownloader, Modifier.fillMaxWidth()) { Text("Download MVDownloader") } }
                item { OutlinedButton(onShare, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("Share MHTalk") } }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@Composable
private fun BetaBadge() {
    Surface(
        color = Color(0xFF5B4AC6),
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8D7CFF)),
    ) {
        Text("BETA", color = Color(0xFFFFF7CA), fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Help center") },
        text = {
            LazyColumn(Modifier.heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Using MHTalk", fontWeight = FontWeight.Bold)
                    Text("MHTalk provides voice, camera, screen sharing, chat and private invite rooms. Only share content you own or are allowed to distribute.", color = MHTalkMuted)
                }
                item {
                    Text("Community safety", fontWeight = FontWeight.Bold)
                    Text("Do not use MHTalk for harassment, threats, sexual exploitation, illegal content, malware, privacy violations or copyright infringement. Public Main messages are moderated; private rooms remain the responsibility of their participants. Long-press a message or open a member profile to report or block.", color = MHTalkMuted)
                }
                item {
                    Text("Privacy", fontWeight = FontWeight.Bold)
                    Text("Voice, video, room chat and live attachments travel through the selected compatible provider. Provider secrets stay on the MHTalk server and are never stored on the phone.", color = MHTalkMuted)
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mhlko-tech/MhlkoTalk/blob/main/MHTalk%20Android/PRIVACY_POLICY.md")))
                        },
                    ) { Text("Open full Privacy Policy") }
                }
                item {
                    Text("Permissions", fontWeight = FontWeight.Bold)
                    Text("Microphone, camera, notifications and screen-capture permissions are requested only when their related feature is used. Screen capture always uses Android's system confirmation.", color = MHTalkMuted)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivateRoomSheet(onDismiss: () -> Unit, onCreate: () -> Unit, onJoin: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF191E31)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
            Text("Private channel", fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            Text("Create a room or enter your friend's invite code.", color = MHTalkMuted)
            Spacer(Modifier.height(20.dp))
            Button(onCreate, Modifier.fillMaxWidth().height(52.dp)) { Text("Create private room", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(code, { code = it.uppercase().take(12) }, Modifier.fillMaxWidth(), label = { Text("MHTALK-0000E") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedButton({ onJoin(code) }, Modifier.fillMaxWidth().height(52.dp), enabled = code.isNotBlank()) { Text("Join private room", fontWeight = FontWeight.Bold) }
        }
    }
}

private fun statusText(status: ConnectionStatus) = when (status) {
    ConnectionStatus.Idle -> "Ready"
    ConnectionStatus.Connecting -> "Connecting"
    ConnectionStatus.Connected -> "Connected"
    ConnectionStatus.Recovering -> "Reconnecting"
    ConnectionStatus.Failed -> "Connection unavailable"
}

private fun statusColor(status: ConnectionStatus) = when (status) {
    ConnectionStatus.Connected -> MHTalkGreen
    ConnectionStatus.Connecting, ConnectionStatus.Recovering -> Color(0xFFFFC857)
    ConnectionStatus.Failed -> Color(0xFFFF7A8D)
    else -> MHTalkMuted
}
