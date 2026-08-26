package com.mhlko.talk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mhlko.talk.auth.AuthState
import com.mhlko.talk.auth.FriendProfile
import com.mhlko.talk.auth.IncomingFriendRequest
import com.mhlko.talk.ui.components.ProfileAvatar
import com.mhlko.talk.ui.theme.MHTalkGreen
import com.mhlko.talk.ui.theme.MHTalkMuted
import kotlinx.coroutines.launch

@Composable
internal fun FriendsDialog(
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
    ProfileAvatar(
        avatar = url,
        name = name,
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
    )
}
