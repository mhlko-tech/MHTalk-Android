package com.mhlko.talk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mhlko.talk.BuildConfig
import com.mhlko.talk.data.SessionUiState
import com.mhlko.talk.data.SubscriptionTier
import com.mhlko.talk.data.subscriptionEntitlements
import com.mhlko.talk.ui.theme.MHTalkMuted

@Composable
internal fun SettingsDialog(
    state: SessionUiState,
    subscriptionTier: SubscriptionTier,
    onDismiss: () -> Unit,
    onOutput: (Int) -> Unit,
    onTestSpeaker: () -> Unit,
    onSwitchCamera: () -> Unit,
    onNoiseCancellation: (Boolean) -> Unit,
    onMessageSounds: (Boolean) -> Unit,
    onPresenceSounds: (Boolean) -> Unit,
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
                Text("Voice processing", fontWeight = FontWeight.Bold)
                SettingSwitch(
                    "Noise cancellation",
                    "Remove background noise from your microphone only. Screen-share audio stays original.",
                    state.noiseCancellationEnabled,
                    onNoiseCancellation,
                )
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
                SettingSwitch("Join and leave", "A sound when a member enters or leaves the room.", state.presenceSoundsEnabled, onPresenceSounds)
                SettingSwitch("Camera activity", "A sound when a member starts their camera.", state.cameraSoundsEnabled, onCameraSounds)
                SettingSwitch("Screen-share activity", "A sound when a member starts sharing their screen.", state.screenShareSoundsEnabled, onScreenSounds)
                Spacer(Modifier.height(18.dp))
                Text("Screen-share privacy", fontWeight = FontWeight.Bold)
                SettingSwitch("Keep notification protection", "Keep Android's privacy protection enabled while sharing. Android may still control this on some devices.", state.screenSharePrivacyEnabled, onScreenPrivacy)
                Spacer(Modifier.height(18.dp))
                val entitlements = subscriptionEntitlements(subscriptionTier)
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("MHTalk ${if (subscriptionTier == SubscriptionTier.Plus) "Plus" else "Free"}", fontWeight = FontWeight.Bold)
                        Text("Your current account plan", color = MHTalkMuted, fontSize = 11.sp)
                        Text("• Clear voice calls and microphone noise cancellation", fontSize = 12.sp)
                        Text("• Camera and screen sharing up to ${if (subscriptionTier == SubscriptionTier.Plus) "1080p" else "720p"}", fontSize = 12.sp)
                        Text("• Files up to ${entitlements.maxAttachmentBytes / 1024 / 1024} MB", fontSize = 12.sp)
                        if (subscriptionTier == SubscriptionTier.Plus) {
                            Text("• Animated profiles, banners, themes and frames", fontSize = 12.sp)
                            Text("• Custom emojis, stickers, soundboard and invites", fontSize = 12.sp)
                        } else {
                            Text(
                                if (BuildConfig.PLAY_DISTRIBUTION) {
                                    "Core calling and safety features remain free. Plus purchases are not offered in the Google Play build."
                                } else {
                                    "Use the yellow help button beside Friends to view available support options. Core calling and safety features remain free."
                                },
                                color = MHTalkMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
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
