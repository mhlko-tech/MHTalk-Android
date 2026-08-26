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
import com.mhlko.talk.data.SessionUiState
import com.mhlko.talk.ui.theme.MHTalkMuted

@Composable
internal fun SettingsDialog(
    state: SessionUiState,
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
