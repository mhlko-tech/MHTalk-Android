package com.mhlko.talk.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mhlko.talk.data.UserProfile
import com.mhlko.talk.data.isImageAvatar
import com.mhlko.talk.ui.theme.MHTalkMuted
import com.mhlko.talk.ui.theme.MHTalkPurple
import kotlinx.coroutines.delay

@Composable
internal fun ProfileDialog(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onChange: (UserProfile) -> Unit,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
) {
    var name by remember(profile.name) { mutableStateOf(profile.name) }
    var bio by remember(profile.bio) { mutableStateOf(profile.bio) }
    var lastEmitted by remember { mutableStateOf(profile.name to profile.bio) }
    fun currentProfile() = profile.copy(name = name.trim().ifBlank { profile.name }, bio = bio.trim())
    LaunchedEffect(name, bio) {
        delay(350)
        val signature = name to bio
        if (name.isNotBlank() && signature != lastEmitted) {
            lastEmitted = signature
            onChange(currentProfile())
        }
    }
    AlertDialog(
        onDismissRequest = { if (name.isNotBlank()) onChange(currentProfile()); onDismiss() },
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
                    onValueChange = { name = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                    supportingText = { Text("يدعم الأسماء العربية والإنجليزية", color = MHTalkMuted) },
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
        confirmButton = {},
        dismissButton = { TextButton(onClick = { if (name.isNotBlank()) onChange(currentProfile()); onDismiss() }) { Text("Close") } },
    )
}

@Composable
internal fun ProfileCropDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onUse: (Float, Float, Float, Int) -> Unit,
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
    var rotation by remember(uri) { mutableIntStateOf(0) }
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
                    Modifier.size(previewSize).background(Color(0xFF101422)),
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
                            rotationZ = rotation.toFloat()
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
                OutlinedButton(onClick = { rotation = (rotation + 90) % 360; offsetX = 0f; offsetY = 0f }) { Text("↻  Rotate") }
            }
        },
        confirmButton = { Button({ onUse(zoom, offsetX, offsetY, rotation) }) { Text("Next") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
