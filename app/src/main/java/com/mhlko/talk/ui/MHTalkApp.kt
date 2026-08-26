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
import android.media.MediaPlayer
import android.graphics.BitmapFactory
import android.os.Build
import android.net.Uri
import android.util.Rational
import android.util.Base64
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.viewinterop.AndroidView
import com.mhlko.talk.BuildConfig
import com.mhlko.talk.R
import com.mhlko.talk.call.SessionViewModel
import com.mhlko.talk.data.ConnectionStatus
import com.mhlko.talk.data.MemberUi
import com.mhlko.talk.data.SessionUiState
import com.mhlko.talk.data.UserProfile
import com.mhlko.talk.data.ChatMessageUi
import com.mhlko.talk.data.ShareQuality
import com.mhlko.talk.auth.AuthRepository
import com.mhlko.talk.auth.AuthRules
import com.mhlko.talk.auth.AuthState
import com.mhlko.talk.auth.FriendProfile
import com.mhlko.talk.auth.IncomingFriendRequest
import com.mhlko.talk.auth.SocialRepository
import com.mhlko.talk.ui.theme.*
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.VideoQuality
import io.livekit.android.renderer.SurfaceViewRenderer
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import kotlin.math.abs

object PipController {
    var inPictureInPicture by mutableStateOf(false)
    var track by mutableStateOf<VideoTrack?>(null)
}

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
        PipVideoScreen(PipController.track, session)
        return
    }
    var pendingShareOptions by remember { mutableStateOf<ShareOptions?>(null) }
    if (!state.launchReady) {
        LaunchScreen()
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
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            pendingShareOptions?.let { options -> session.startScreenShare(result.data!!, options.includeMicrophone, options.quality) }
        }
    }
    fun withCallPermission(action: () -> Unit) {
        val missing = buildList {
            add(Manifest.permission.RECORD_AUDIO)
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
    var shareOptionsOpen by remember { mutableStateOf(false) }
    var pendingProfilePhoto by remember { mutableStateOf<Uri?>(null) }
    val profilePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) pendingProfilePhoto = uri
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
        )
    }
    LaunchedEffect(state.localProfile.avatar, authState) {
        if (authState is AuthState.SignedIn && state.localProfile.avatar.startsWith("data:image/")) {
            runCatching { auth.updateProfile(state.localProfile.name, state.localProfile.bio, state.localProfile.avatar) }
                .onFailure { session.showNotice(it.message ?: "Could not sync profile photo") }
        }
    }
    Scaffold(
        containerColor = MHTalkBackground,
        bottomBar = {
            if (state.roomName != null) NavigationBar(containerColor = Color(0xFF101422)) {
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
        onSave = {
            session.saveProfile(it)
            if (authState is AuthState.SignedIn) appScope.launch {
                runCatching { auth.updateProfile(it.name, it.bio) }
                    .onFailure { session.showNotice(it.message ?: "Could not sync profile") }
            }
            showProfile = false
        },
        onChoosePhoto = { profilePhotoPicker.launch(arrayOf("image/*")) },
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
        onDismiss = { showSettings = false },
        onOutput = session::setOutputLevel,
        onTestSpeaker = session::testSpeaker,
        onSwitchCamera = session::switchCamera,
        onMessageSounds = session::setMessageSounds,
        onCameraSounds = session::setCameraSounds,
        onScreenSounds = session::setScreenShareSounds,
        onScreenPrivacy = session::setScreenSharePrivacy,
    )
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
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
            onDismiss = { pendingProfilePhoto = null },
            onUse = { zoom, x, y ->
                session.chooseProfilePhoto(uri, zoom, x, y)
                pendingProfilePhoto = null
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
                    item { Text("MHTalk may process voice, video, screen share, profile details, messages and files only to provide realtime communication. LiveKit carries realtime media and the MHTalk service issues secure room access.", color = MHTalkMuted) }
                    item { Text("Public Main messages are filtered. You can long-press messages and open member profiles to report or block users. Reports are retained for up to 30 days for safety review.", color = MHTalkMuted) }
                    item { Text("By continuing, you agree to these Terms of Use and the Privacy Policy shown in Help.", color = MHTalkMuted) }
                }
            },
            confirmButton = { Button(session::acceptTerms) { Text("Accept and continue") } },
        )
    }
    state.privateCode?.let { code ->
        AlertDialog(
            onDismissRequest = session::clearPrivateCode,
            title = { Text("Private room created") },
            text = {
                Column {
                    Text("Send this code to your friend:", color = MHTalkMuted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(code, color = MHTalkPurple, fontWeight = FontWeight.Black, fontSize = 24.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("MHTalk private code", code))
                            session.showNotice("Code copied")
                        }) { Icon(Icons.Rounded.ContentCopy, "Copy code") }
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
    state.updateVersion?.let { version ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Update available") },
            text = { Text("MHTalk $version is required to continue. Update now to use rooms and calls.") },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mhlko-tech/MHTalk-Android/releases/latest")))
                }) { Text("Update") }
            },
        )
    }
}

@Composable
private fun LaunchScreen() {
    Box(Modifier.fillMaxSize().background(MHTalkBackground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(108.dp).clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(MHTalkPurple, Color(0xFF40359D)))), contentAlignment = Alignment.Center) {
                Text("M", color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(18.dp))
            Text("MHTalk ${BuildConfig.VERSION_NAME}", color = MHTalkText, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MHTalkGreen)
        }
    }
}

@Composable
private fun Header(
    state: SessionUiState,
    onEditProfile: () -> Unit,
    onFriends: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onReport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MHTalkSurface).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isImageAvatar(state.localProfile.avatar)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(MHTalkPurple), contentAlignment = Alignment.Center) {
                Text(state.localProfile.avatar.take(2).ifBlank { "MH" }.uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
        } else {
            AsyncImage(
                model = state.localProfile.avatar,
                contentDescription = "Profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)),
            )
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(if (state.roomName == null) "MHTalk ${BuildConfig.VERSION_NAME}" else if (state.roomName == "Main") "Main channel" else "Private channel", fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
            Text(statusText(state.status), color = statusColor(state.status), fontSize = 12.sp)
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
                    text = { Text("Friends") },
                    leadingIcon = { Icon(Icons.Rounded.People, null) },
                    onClick = { menuOpen = false; onFriends() },
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                    onClick = { menuOpen = false; onSettings() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Help") },
                    leadingIcon = { Icon(Icons.Rounded.HelpOutline, null) },
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
                    Spacer(Modifier.height(8.dp))
                    session.videoTrack(null, Track.Source.CAMERA)?.let { VideoTile(it, session, "Your camera") }
                }
                if (state.screenShareEnabled) {
                    Spacer(Modifier.height(8.dp))
                    session.videoTrack(null, Track.Source.SCREEN_SHARE)?.let {
                        VideoTile(it, session, "Your screen", isScreenShare = true)
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
                        if (member.cameraEnabled) session.videoTrack(member.identity, Track.Source.CAMERA)?.let { VideoTile(it, session, "${member.name}'s camera") }
                        if (member.screenShareEnabled) session.videoTrack(member.identity, Track.Source.SCREEN_SHARE)?.let {
                            VideoTile(it, session, "${member.name}'s screen", isScreenShare = true, member = member)
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
                    if (!isImageAvatar(member.avatar)) {
                        Box(Modifier.size(110.dp).clip(CircleShape).background(MHTalkPurple), contentAlignment = Alignment.Center) {
                            Text(member.avatar.take(1).ifBlank { member.name.take(1) }.uppercase(), fontSize = 38.sp, fontWeight = FontWeight.Black)
                        }
                    } else {
                        AsyncImage(
                            model = member.avatar,
                            contentDescription = member.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(110.dp).clip(CircleShape).clickable { memberAvatarPreview = member.avatar },
                        )
                    }
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
        androidx.compose.ui.window.Dialog(onDismissRequest = { memberAvatarPreview = null }) {
            Box(Modifier.fillMaxWidth().background(Color.Black).padding(14.dp), contentAlignment = Alignment.Center) {
                AsyncImage(model = avatar, contentDescription = "Profile photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            }
        }
    }
}

private data class ShareOptions(val includeMicrophone: Boolean, val quality: ShareQuality)

@Composable
private fun ShareOptionsDialog(onDismiss: () -> Unit, onStart: (ShareOptions) -> Unit) {
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
                    Row(Modifier.fillMaxWidth().clickable { quality = option }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(quality == option, { quality = option })
                        Text(option.name)
                    }
                }
            }
        },
        confirmButton = { TextButton({ onStart(ShareOptions(includeMic, quality)) }) { Text("Continue") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun VideoTile(
    track: VideoTrack,
    session: SessionViewModel,
    label: String,
    isScreenShare: Boolean = false,
    member: MemberUi? = null,
) {
    val context = LocalContext.current
    var controlsVisible by remember(track) { mutableStateOf(isScreenShare) }
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
                Modifier.fillMaxSize().clickable { if (isScreenShare) controlsVisible = !controlsVisible },
            ) {
                if (!isScreenShare || controlsVisible) {
                    Text(
                        label,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp),
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                }
                if (isScreenShare && controlsVisible) {
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
                        IconButton(
                            onClick = { fullScreen = true },
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xB8000000)),
                        ) { Icon(Icons.Rounded.Fullscreen, "Full screen") }
                        if (member != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            IconButton(
                                onClick = {
                                    PipController.track = track
                                    (context as? Activity)?.enterPictureInPictureMode(
                                        PictureInPictureParams.Builder()
                                            .setAspectRatio(Rational((aspectRatio * 1_000).toInt().coerceAtLeast(1), 1_000))
                                            .build(),
                                    )
                                },
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xB8000000)),
                            ) { Icon(Icons.Rounded.PictureInPictureAlt, "Picture in picture") }
                        }
                    }
                }
            }
        }
    }

    if (fullScreen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { fullScreen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            var fullControlsVisible by remember { mutableStateOf(true) }
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable { fullControlsVisible = !fullControlsVisible },
                contentAlignment = Alignment.Center,
            ) {
                AdaptiveVideoRenderer(
                    track = track,
                    session = session,
                    modifier = Modifier.fillMaxSize(),
                    onAspectRatio = { aspectRatio = it },
                )
                if (fullControlsVisible) {
                    IconButton(
                        onClick = { fullScreen = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(18.dp).size(48.dp).clip(CircleShape).background(Color(0xB8000000)),
                    ) { Icon(Icons.Rounded.FullscreenExit, "Exit full screen") }
                }
            }
        }
    }
}

@Composable
private fun PipVideoScreen(track: VideoTrack?, session: SessionViewModel) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        track?.let { videoTrack ->
            AdaptiveVideoRenderer(
                track = videoTrack,
                session = session,
                modifier = Modifier.fillMaxSize(),
                onAspectRatio = { ratio ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        (context as? Activity)?.setPictureInPictureParams(
                            PictureInPictureParams.Builder()
                                .setAspectRatio(Rational((ratio * 1_000).toInt().coerceAtLeast(1), 1_000))
                                .build(),
                        )
                    }
                },
            )
        }
    }
}

private enum class AuthMode { Login, Register, Forgot, Verification, RecoveryCode, Reset }

@Composable
private fun RequiredSignInScreen(authState: AuthState, auth: AuthRepository, onRetry: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AuthMode.Login) }
    var identifier by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var authAvatar by remember { mutableStateOf<String?>(null) }
    var onboardingCodeSent by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var acceptedTerms by remember { mutableStateOf(false) }
    var localBusy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var resendSeconds by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val authPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching {
            val mime = context.contentResolver.getType(uri).orEmpty().takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Could not read this image")
            require(bytes.size <= 5 * 1024 * 1024) { "Choose an image that is 5 MB or smaller" }
            authAvatar = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }.onFailure { localError = it.message ?: "Could not read this image" }
    }
    val busy = localBusy || authState == AuthState.Checking || authState == AuthState.Authenticating

    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.AwaitingVerification -> { email = authState.email; mode = AuthMode.Verification }
            is AuthState.Onboarding -> {
                email = authState.email; username = authState.username; displayName = authState.displayName
                authAvatar = authState.avatarUrl; verificationCode = ""; onboardingCodeSent = false
            }
            AuthState.PasswordRecovery -> mode = AuthMode.Reset
            else -> Unit
        }
    }
    LaunchedEffect(resendSeconds) {
        if (resendSeconds > 0) { delay(1_000); resendSeconds-- }
    }
    fun switchMode(next: AuthMode) {
        auth.clearAuthError(); mode = next; localError = ""; notice = ""; verificationCode = ""
    }
    fun perform(block: suspend () -> Unit) {
        scope.launch {
            localBusy = true; localError = ""; notice = ""
            runCatching { block() }.onFailure { localError = it.message ?: "Something went wrong. Try again." }
            localBusy = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF11152A), Color(0xFF090C16))),
        ).padding(26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1E32)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF434A70)),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 25.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Box(
                        Modifier.size(58.dp).clip(RoundedCornerShape(17.dp)).background(
                            Brush.linearGradient(listOf(Color(0xFF8B78FF), Color(0xFF5B4ADE))),
                        ), contentAlignment = Alignment.Center,
                    ) { Text("M", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black) }
                    Column {
                        Text("MHTalk", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Text("Voice, video and rooms · v${BuildConfig.VERSION_NAME}", color = MHTalkMuted, fontSize = 11.sp)
                    }
                }
                HorizontalDivider(color = Color(0xFF303650))

                if (authState == AuthState.Checking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Restoring your secure session…", color = MHTalkMuted)
                    }
                } else if (authState is AuthState.Onboarding) {
                    androidx.compose.foundation.Image(painterResource(R.drawable.ic_google_logo), null, Modifier.size(32.dp))
                    Text(
                        if (onboardingCodeSent) "Verify account creation" else "Finish your MHTalk account",
                        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    )
                    if (!onboardingCodeSent) {
                        Text("Google verified ${authState.email}. Choose how your MHTalk profile will appear.", color = MHTalkMuted, lineHeight = 20.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (authAvatar?.startsWith("data:image/") == true || authAvatar?.startsWith("http") == true) {
                                AsyncImage(authAvatar, "Profile photo", Modifier.size(68.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            } else {
                                Box(Modifier.size(68.dp).clip(CircleShape).background(Color(0xFF5B4ADE)), contentAlignment = Alignment.Center) {
                                    Text(displayName.take(1).ifBlank { "M" }.uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            Column {
                                TextButton({ authPhotoPicker.launch(arrayOf("image/*")) }) { Text("Choose profile photo") }
                                if (!authAvatar.isNullOrBlank()) TextButton({ authAvatar = null }) { Text("Remove photo") }
                            }
                        }
                        OutlinedTextField(authState.email, {}, Modifier.fillMaxWidth(), readOnly = true, label = { Text("Email") })
                        OutlinedTextField(displayName, { displayName = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
                        OutlinedTextField(username, { username = it.filter { char -> char.isLetterOrDigit() || char == '_' }.take(32) }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
                        if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        Button(
                            onClick = { perform {
                                require(AuthRules.usernameError(username) == null) { AuthRules.usernameError(username)!! }
                                require(displayName.isNotBlank()) { "Enter a display name" }
                                auth.startGoogleOnboarding(); verificationCode = ""; onboardingCodeSent = true; resendSeconds = 60
                            } }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(49.dp),
                        ) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Send account creation code") }
                    } else {
                        Text("Enter the account creation code sent to ${authState.email}.", color = MHTalkMuted, lineHeight = 20.sp)
                        OutlinedTextField(
                            verificationCode, { verificationCode = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(),
                            label = { Text("Account creation code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        )
                        if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        if (notice.isNotBlank()) Text(notice, color = Color(0xFF8EE5BD), fontSize = 12.sp)
                        Button(
                            onClick = { perform { auth.completeGoogleOnboarding(username, displayName, authAvatar, verificationCode) } },
                            enabled = !busy && verificationCode.length >= 6, modifier = Modifier.fillMaxWidth().height(49.dp),
                        ) { Text("Verify and enter MHTalk", fontWeight = FontWeight.Bold) }
                        TextButton(
                            onClick = { perform { auth.startGoogleOnboarding(); resendSeconds = 60; notice = "A new account creation code was sent." } },
                            enabled = !busy && resendSeconds == 0,
                        ) { Text(if (resendSeconds > 0) "Resend in ${resendSeconds}s" else "Resend account creation code") }
                        TextButton({ onboardingCodeSent = false; localError = ""; notice = "" }) { Text("Edit profile details") }
                    }
                    TextButton({ perform { auth.signOut() } }) { Text("Cancel and sign out") }
                } else if (authState is AuthState.AccountExists) {
                    Icon(Icons.Rounded.Info, null, tint = Color(0xFFA99CFF), modifier = Modifier.size(48.dp))
                    Text("Account already exists", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(authState.message, color = MHTalkMuted, lineHeight = 20.sp)
                    Text(authState.email, color = Color.White, fontWeight = FontWeight.Bold)
                    if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Button(
                        onClick = { perform {
                            auth.requestPasswordReset(authState.email); auth.dismissAccountNotice()
                            email = authState.email; verificationCode = ""; mode = AuthMode.RecoveryCode
                            notice = "A password setup code was sent."
                        } }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(49.dp),
                    ) { Text(if (authState.passwordEnabled) "Reset password" else "Set a password") }
                    if (authState.googleLinked) Button(
                        onClick = { auth.beginSignIn("google") }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(49.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF222532)),
                    ) { androidx.compose.foundation.Image(painterResource(R.drawable.ic_google_logo), null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Log in using Google") }
                    TextButton({ auth.dismissAccountNotice(); switchMode(AuthMode.Login) }) { Text("Back to login") }
                } else if (mode == AuthMode.Verification) {
                    Icon(Icons.Rounded.MarkEmailRead, null, tint = Color(0xFFA99CFF), modifier = Modifier.size(48.dp))
                    Text("Verify your email", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Enter the verification code sent to $email.", color = MHTalkMuted, lineHeight = 20.sp)
                    OutlinedTextField(
                        verificationCode, { verificationCode = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(),
                        label = { Text("Verification code") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    )
                    if (localError.isNotBlank()) Text(localError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (notice.isNotBlank()) Text(notice, color = Color(0xFF8EE5BD), fontSize = 12.sp)
                    Button(
                        onClick = { perform { auth.verifyEmailCode(email, verificationCode, displayName, authAvatar) } },
                        enabled = !busy && verificationCode.length >= 6, modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("Verify and continue") }
                    Button(
                        onClick = { perform { auth.resendVerification(email); resendSeconds = 60; notice = "A new verification code was sent." } },
                        enabled = !busy && resendSeconds == 0, modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text(if (resendSeconds > 0) "Resend in ${resendSeconds}s" else "Resend verification code") }
                    TextButton(onClick = { identifier = email; switchMode(AuthMode.Forgot) }) { Text("Already use this email? Set a password") }
                    TextButton(onClick = { switchMode(AuthMode.Login) }) { Text("Back to login") }
                } else {
                    Text(
                        when (mode) { AuthMode.Login -> "Welcome back"; AuthMode.Register -> "Create your account"; AuthMode.Forgot -> "Reset your password"; AuthMode.RecoveryCode -> "Enter recovery code"; else -> "Choose a new password" },
                        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when (mode) { AuthMode.Login -> "Sign in to continue to MHTalk."; AuthMode.Register -> "One account works on phone and PC."; AuthMode.Forgot -> "Enter your username or email and we’ll send a recovery code."; AuthMode.RecoveryCode -> "Enter the code sent to $email."; else -> "Use at least 10 characters for your new password." },
                        color = MHTalkMuted, fontSize = 13.sp,
                    )

                    if (mode == AuthMode.Register) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (authAvatar?.startsWith("data:image/") == true) {
                                AsyncImage(authAvatar, "Profile photo", Modifier.size(62.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                            } else {
                                Box(Modifier.size(62.dp).clip(CircleShape).background(Color(0xFF5B4ADE)), contentAlignment = Alignment.Center) {
                                    Text(displayName.take(1).ifBlank { "M" }.uppercase(), color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            Column {
                                TextButton({ authPhotoPicker.launch(arrayOf("image/*")) }) { Text("Choose profile photo") }
                                if (!authAvatar.isNullOrBlank()) TextButton({ authAvatar = null }) { Text("Remove photo") }
                            }
                        }
                        OutlinedTextField(username, { username = it.filter { char -> char.isLetterOrDigit() || char == '_' }.take(32) }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next))
                        OutlinedTextField(displayName, { displayName = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                    }
                    if (mode == AuthMode.Login || mode == AuthMode.Forgot) {
                        OutlinedTextField(identifier, { identifier = it }, Modifier.fillMaxWidth(), label = { Text("Username or Email") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = if (mode == AuthMode.Forgot) ImeAction.Done else ImeAction.Next))
                    }
                    if (mode == AuthMode.RecoveryCode) {
                        OutlinedTextField(
                            verificationCode, { verificationCode = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(),
                            label = { Text("Recovery code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        )
                    }
                    if (mode == AuthMode.Login || mode == AuthMode.Register || mode == AuthMode.Reset) {
                        OutlinedTextField(
                            password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton({ passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (mode == AuthMode.Login) ImeAction.Done else ImeAction.Next),
                        )
                    }
                    if (mode == AuthMode.Register || mode == AuthMode.Reset) {
                        OutlinedTextField(confirmation, { confirmation = it }, Modifier.fillMaxWidth(), label = { Text("Confirm password") }, singleLine = true, visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                    }
                    if (mode == AuthMode.Login) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ switchMode(AuthMode.Register) }, contentPadding = PaddingValues(0.dp)) { Text("Register new account", fontSize = 12.sp) }
                            TextButton({ switchMode(AuthMode.Forgot) }, contentPadding = PaddingValues(0.dp)) { Text("Forgot password?", fontSize = 12.sp) }
                        }
                    }
                    if (mode == AuthMode.Register) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Checkbox(acceptedTerms, { acceptedTerms = it })
                            Column(Modifier.padding(top = 7.dp)) {
                                Text("I agree to the:", color = MHTalkMuted, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mhtalk-token-service.mhlkotalk.workers.dev/terms"))) },
                                        contentPadding = PaddingValues(0.dp),
                                    ) { Text("Terms of Service", fontSize = 12.sp) }
                                    Text("and", color = MHTalkMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 13.dp))
                                    TextButton(
                                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mhtalk-token-service.mhlkotalk.workers.dev/privacy"))) },
                                        contentPadding = PaddingValues(0.dp),
                                    ) { Text("Privacy Policy", fontSize = 12.sp) }
                                }
                            }
                        }
                    }

                    val displayedError = localError.ifBlank { (authState as? AuthState.Failed)?.message.orEmpty() }
                    if (displayedError.isNotBlank()) Text(displayedError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (notice.isNotBlank()) Text(notice, color = Color(0xFF8EE5BD), fontSize = 12.sp)
                    if (authState == AuthState.Unavailable) {
                        Text("Account service is unavailable. Check your connection and try again.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                    }

                    Button(
                        enabled = !busy && (mode != AuthMode.RecoveryCode || verificationCode.length >= 6),
                        modifier = Modifier.fillMaxWidth().height(49.dp),
                        onClick = {
                            perform {
                                when (mode) {
                                    AuthMode.Login -> auth.login(identifier, password)
                                    AuthMode.Register -> {
                                        require(AuthRules.usernameError(username) == null) { AuthRules.usernameError(username)!! }
                                        require(AuthRules.passwordError(password) == null) { AuthRules.passwordError(password)!! }
                                        require(password == confirmation) { "Passwords do not match" }
                                        require(acceptedTerms) { "Accept the Terms and Privacy Policy to continue" }
                                        require(auth.usernameAvailable(username)) { "Username is unavailable" }
                                        auth.register(username, displayName, email, password)
                                    }
                                    AuthMode.Forgot -> {
                                        auth.requestPasswordReset(identifier); email = identifier.trim(); verificationCode = ""
                                        mode = AuthMode.RecoveryCode; notice = "If an account matches this information, a recovery code has been sent."
                                    }
                                    AuthMode.RecoveryCode -> auth.verifyPasswordRecoveryCode(email, verificationCode)
                                    AuthMode.Reset -> {
                                        require(AuthRules.passwordError(password) == null) { AuthRules.passwordError(password)!! }
                                        require(password == confirmation) { "Passwords do not match" }
                                        auth.completePasswordRecovery(password)
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text(when (mode) { AuthMode.Login -> "Login"; AuthMode.Register -> "Create account"; AuthMode.Forgot -> "Send recovery code"; AuthMode.RecoveryCode -> "Verify code"; else -> "Save new password" }, fontWeight = FontWeight.Bold)
                    }

                    if (mode == AuthMode.Login) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { HorizontalDivider(Modifier.weight(1f), color = Color(0xFF343A57)); Text("  OR  ", color = MHTalkMuted, fontSize = 10.sp); HorizontalDivider(Modifier.weight(1f), color = Color(0xFF343A57)) }
                        Button(
                            onClick = { auth.beginSignIn("google") }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(49.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF222532)),
                        ) { androidx.compose.foundation.Image(painterResource(R.drawable.ic_google_logo), null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Log in using Google", fontWeight = FontWeight.Bold) }
                    } else if (mode != AuthMode.Reset) {
                        TextButton({ switchMode(if (mode == AuthMode.RecoveryCode) AuthMode.Forgot else AuthMode.Login) }) { Text(if (mode == AuthMode.RecoveryCode) "Use another email" else "Back to login") }
                    } else {
                        TextButton({ perform { auth.cancelPasswordRecovery() } }) { Text("Cancel") }
                    }
                }
                HorizontalDivider(color = Color(0xFF303650))
                Text(
                    "Protected sign-in · Your password is never stored by MHTalk",
                    color = Color(0xFF858EAC),
                    fontSize = 10.sp,
                    lineHeight = 17.sp,
                )
            }
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
            if (!isImageAvatar(member.avatar)) {
                Box(Modifier.size(43.dp).clip(CircleShape).background(if (mine) MHTalkPurple else Color(0xFF343A59)), contentAlignment = Alignment.Center) {
                    Text(member.avatar.take(1).ifBlank { member.name.take(1) }.uppercase(), fontWeight = FontWeight.Black)
                }
            } else {
                AsyncImage(model = member.avatar, contentDescription = member.name, contentScale = ContentScale.Crop, modifier = Modifier.size(43.dp).clip(CircleShape))
            }
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
                                            Icon(Icons.Rounded.InsertDriveFile, null, tint = MHTalkPurple)
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
                ) { Icon(Icons.Rounded.Send, "Send") }
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
                MessageAction(Icons.Rounded.Reply, "Reply", onReply)
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
private fun ProfileDialog(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (UserProfile) -> Unit,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
) {
    var name by remember(profile.name) { mutableStateOf(profile.name) }
    var bio by remember(profile.bio) { mutableStateOf(profile.bio) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit profile") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!isImageAvatar(profile.avatar)) {
                    Box(Modifier.size(92.dp).clip(CircleShape).background(MHTalkPurple), contentAlignment = Alignment.Center) {
                        Text(profile.avatar.take(1).ifBlank { name.take(1) }.uppercase(), fontSize = 28.sp, fontWeight = FontWeight.Black)
                    }
                } else {
                    AsyncImage(model = profile.avatar, contentDescription = "Profile photo", contentScale = ContentScale.Crop, modifier = Modifier.size(92.dp).clip(CircleShape))
                }
                Row {
                    TextButton(onChoosePhoto) { Text("Choose photo") }
                    if (profile.avatar.isNotBlank()) TextButton(onRemovePhoto) { Text("Remove") }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it.take(160) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Bio") },
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(profile.copy(name = name.trim().ifBlank { "Me" }, bio = bio.trim())) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProfileCropDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onUse: (Float, Float, Float) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val mimeType = remember(uri) { context.contentResolver.getType(uri).orEmpty() }
    val animated = mimeType.equals("image/gif", ignoreCase = true)
    val dimensions = remember(uri) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        options.outWidth.coerceAtLeast(1) to options.outHeight.coerceAtLeast(1)
    }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(uri) { mutableFloatStateOf(0f) }
    val previewSize = 230.dp
    val previewPx = with(density) { previewSize.toPx() }
    val baseScale = maxOf(previewPx / dimensions.first, previewPx / dimensions.second)
    val translationX = offsetX * ((dimensions.first * baseScale * zoom - previewPx).coerceAtLeast(0f) / 2f)
    val translationY = offsetY * ((dimensions.second * baseScale * zoom - previewPx).coerceAtLeast(0f) / 2f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop profile photo") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(previewSize).clip(CircleShape).background(Color(0xFF101422)),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Exact avatar preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            this.translationX = translationX
                            this.translationY = translationY
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (animated) {
                    Text("Animated image · the centered circular crop is preserved.", color = MHTalkMuted, fontSize = 12.sp)
                } else {
                    Text("Zoom · ${(zoom * 100).toInt()}%", modifier = Modifier.fillMaxWidth(), color = MHTalkMuted)
                    Slider(zoom, { zoom = it }, valueRange = 1f..3f)
                    Text("Move left / right", modifier = Modifier.fillMaxWidth(), color = MHTalkMuted)
                    Slider(offsetX, { offsetX = it }, valueRange = -1f..1f)
                    Text("Move up / down", modifier = Modifier.fillMaxWidth(), color = MHTalkMuted)
                    Slider(offsetY, { offsetY = it }, valueRange = -1f..1f)
                }
            }
        },
        confirmButton = { TextButton({ onUse(zoom, offsetX, offsetY) }) { Text("Use this photo") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FriendsDialog(
    authState: AuthState,
    friends: List<FriendProfile>,
    requests: List<IncomingFriendRequest>,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onGoogle: () -> Unit,
    onFacebook: () -> Unit,
    onSignOut: () -> Unit,
    onSearch: suspend (String) -> List<FriendProfile>,
    onAdd: suspend (String) -> Unit,
    onRespond: suspend (String, Boolean) -> Unit,
    onInvite: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FriendProfile>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Friends") },
        text = {
            when (authState) {
                AuthState.Unavailable -> Text("Accounts are ready. Add the Supabase project URL and publishable key to activate them.", color = MHTalkMuted)
                AuthState.Checking, AuthState.SignedOut, AuthState.Authenticating, AuthState.PasswordRecovery,
                is AuthState.AwaitingVerification, is AuthState.AccountExists, is AuthState.Onboarding,
                is AuthState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sign in to use the same profile and friends on phone and PC.", color = MHTalkMuted)
                    Button(onGoogle, Modifier.fillMaxWidth(), enabled = authState != AuthState.Authenticating) { Text("Continue with Google") }
                    OutlinedButton(onFacebook, Modifier.fillMaxWidth(), enabled = authState != AuthState.Authenticating) { Text("Continue with Facebook") }
                    if (authState is AuthState.Failed) Text(authState.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                is AuthState.SignedIn -> LazyColumn(
                    Modifier.heightIn(max = 570.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SocialAvatar(authState.account.avatarUrl, authState.account.displayName)
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(authState.account.displayName, fontWeight = FontWeight.Bold)
                                Text("@${authState.account.username}", color = MHTalkMuted, fontSize = 12.sp)
                            }
                            TextButton(onSignOut) { Text("Sign out") }
                        }
                    }
                    if (requests.isNotEmpty()) {
                        item { Text("FRIEND REQUESTS", color = MHTalkMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                        items(requests, key = { it.requestId }) { request ->
                            SocialPerson(request.profile) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { scope.launch { onRespond(request.requestId, true) } }) { Text("Accept") }
                                    IconButton(onClick = { scope.launch { onRespond(request.requestId, false) } }) { Icon(Icons.Rounded.Close, "Decline") }
                                }
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("Name or @username") },
                                singleLine = true,
                            )
                            IconButton(
                                enabled = query.trim().length >= 2 && busy != "search",
                                onClick = {
                                    busy = "search"
                                    scope.launch {
                                        runCatching { onSearch(query.trim()) }
                                            .onSuccess { results = it }
                                            .onFailure { localError = it.message }
                                        busy = null
                                    }
                                },
                            ) { Icon(Icons.Rounded.Search, "Search") }
                        }
                    }
                    items(results, key = { "search-${it.id}" }) { profile ->
                        SocialPerson(profile) {
                            TextButton(
                                enabled = !profile.isFriend && busy != profile.id,
                                onClick = {
                                    busy = profile.id
                                    scope.launch {
                                        runCatching { onAdd(profile.id) }
                                            .onSuccess { results = results.filterNot { it.id == profile.id } }
                                            .onFailure { localError = it.message }
                                        busy = null
                                    }
                                },
                            ) { Text(if (profile.isFriend) "Friends" else "Add") }
                        }
                    }
                    item { Text("YOUR FRIENDS", color = MHTalkMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (!loading && friends.isEmpty()) item { Text("No friends yet. Search by name or username.", color = MHTalkMuted) }
                    items(friends, key = FriendProfile::id) { friend ->
                        SocialPerson(friend, showPresence = true) {
                            Button(onClick = { onInvite(friend.id) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)) { Text("Invite") }
                        }
                    }
                    (localError ?: error)?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) } }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SocialPerson(profile: FriendProfile, showPresence: Boolean = false, action: @Composable () -> Unit) {
    Surface(color = Color(0xFF23283D), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                SocialAvatar(profile.avatarUrl, profile.displayName)
                if (showPresence) Box(
                    Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape)
                        .background(if (profile.online) MHTalkGreen else Color(0xFF747B92)),
                )
            }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(profile.displayName, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(if (showPresence) "${if (profile.online) "Online" else "Offline"} · @${profile.username}" else "@${profile.username}", color = MHTalkMuted, fontSize = 11.sp, maxLines = 1)
            }
            action()
        }
    }
}

@Composable
private fun SocialAvatar(url: String?, name: String) {
    if (!url.isNullOrBlank()) AsyncImage(url, name, contentScale = ContentScale.Crop, modifier = Modifier.size(42.dp).clip(CircleShape))
    else Box(Modifier.size(42.dp).clip(CircleShape).background(MHTalkPurple), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SettingsDialog(
    state: SessionUiState,
    onDismiss: () -> Unit,
    onOutput: (Int) -> Unit,
    onTestSpeaker: () -> Unit,
    onSwitchCamera: () -> Unit,
    onMessageSounds: (Boolean) -> Unit,
    onCameraSounds: (Boolean) -> Unit,
    onScreenSounds: (Boolean) -> Unit,
    onScreenPrivacy: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            LazyColumn(Modifier.heightIn(max = 510.dp)) {
                item { Column {
                Text("Speaker", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Output level · ${state.outputLevel}%", color = MHTalkMuted)
                Slider(
                    value = state.outputLevel.toFloat(),
                    onValueChange = { onOutput(it.toInt()) },
                    valueRange = 0f..100f,
                )
                OutlinedButton(onTestSpeaker, Modifier.fillMaxWidth()) { Text("Test speaker") }
                Spacer(Modifier.height(18.dp))
                Text("Camera", fontWeight = FontWeight.Bold)
                Text("Android uses the selected system camera.", color = MHTalkMuted, fontSize = 12.sp)
                OutlinedButton(
                    onClick = onSwitchCamera,
                    enabled = state.cameraEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Switch front / back") }
                Spacer(Modifier.height(18.dp))
                Text("Event sounds", fontWeight = FontWeight.Bold)
                SettingSwitch("New messages", "A soft sound when someone sends a message.", state.messageSoundsEnabled, onMessageSounds)
                SettingSwitch("Camera activity", "A sound when a member starts their camera.", state.cameraSoundsEnabled, onCameraSounds)
                SettingSwitch("Screen-share activity", "A sound when a member starts sharing their screen.", state.screenShareSoundsEnabled, onScreenSounds)
                Spacer(Modifier.height(18.dp))
                Text("Screen-share privacy", fontWeight = FontWeight.Bold)
                SettingSwitch("Keep notification protection", "Keep Android's privacy protection enabled while sharing. Android may still control this on some devices.", state.screenSharePrivacyEnabled, onScreenPrivacy)
                } }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(detail, color = MHTalkMuted, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
                    Text("Voice and video travel through LiveKit for realtime delivery. Files are transferred to connected room participants. MHTalk does not require your LiveKit secret on the phone.", color = MHTalkMuted)
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

private fun isImageAvatar(value: String): Boolean {
    val normalized = value.lowercase()
    return normalized.startsWith("data:image/") ||
        normalized.startsWith("content://") ||
        normalized.startsWith("file://") ||
        normalized.startsWith("http://") ||
        normalized.startsWith("https://")
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
