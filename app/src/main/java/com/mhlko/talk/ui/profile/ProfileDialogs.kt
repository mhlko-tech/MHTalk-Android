package com.mhlko.talk.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface

@Composable
internal fun ProfileDialog(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onChange: (UserProfile) -> Unit,
    onChoosePhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    savingPhoto: Boolean,
) {
    var name by remember(profile.name) { mutableStateOf(profile.name) }
    var bio by remember(profile.bio) { mutableStateOf(profile.bio) }
    fun currentProfile() = profile.copy(name = name.trim().ifBlank { profile.name }, bio = bio.trim())
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
                    TextButton(onChoosePhoto, enabled = !savingPhoto) { Text("Choose photo") }
                    if (profile.avatar.isNotBlank()) TextButton(onRemovePhoto, enabled = !savingPhoto) { Text("Remove") }
                }
                if (savingPhoto) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Updating profile photo…", color = MHTalkMuted, fontSize = 12.sp)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(60)
                        if (name.isNotBlank()) onChange(currentProfile())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                    supportingText = { Text("يدعم الأسماء العربية والإنجليزية", color = MHTalkMuted) },
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = bio,
                    onValueChange = {
                        bio = it.take(160)
                        onChange(currentProfile())
                    },
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
    saving: Boolean,
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
        val exifRotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use(::ExifInterface)?.rotationDegrees ?: 0
        }.getOrDefault(0)
        if (exifRotation % 180 == 0) {
            options.outWidth.coerceAtLeast(1) to options.outHeight.coerceAtLeast(1)
        } else {
            options.outHeight.coerceAtLeast(1) to options.outWidth.coerceAtLeast(1)
        }
    }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(uri) { mutableFloatStateOf(0f) }
    var rotation by remember(uri) { mutableIntStateOf(0) }
    var previewPx by remember(uri) { mutableFloatStateOf(1f) }
    val rotatedWidth = if (rotation % 180 == 0) dimensions.first else dimensions.second
    val rotatedHeight = if (rotation % 180 == 0) dimensions.second else dimensions.first
    val baseScale = maxOf(previewPx / rotatedWidth, previewPx / rotatedHeight)
    val maxPanX = ((rotatedWidth * baseScale * zoom - previewPx) / 2f).coerceAtLeast(0f)
    val maxPanY = ((rotatedHeight * baseScale * zoom - previewPx) / 2f).coerceAtLeast(0f)
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        if (!animated && !saving) {
            zoom = (zoom * zoomChange).coerceIn(1f, 4f)
            if (maxPanX > 0f) offsetX = (offsetX + panChange.x / maxPanX).coerceIn(-1f, 1f)
            if (maxPanY > 0f) offsetY = (offsetY + panChange.y / maxPanY).coerceIn(-1f, 1f)
        }
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF08090C)).statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Rounded.ArrowBack, "Back") }
                Text("Crop & rotate", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val cropSize = minOf(maxWidth, maxHeight)
                val imageWidth = with(density) { (dimensions.first * baseScale).toDp() }
                val imageHeight = with(density) { (dimensions.second * baseScale).toDp() }
                Box(
                    Modifier.size(cropSize).clipToBounds().background(Color.Black)
                        .transformable(transformState)
                        .border(2.dp, Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    SideEffect { previewPx = with(density) { cropSize.toPx() } }
                    AsyncImage(
                        model = uri,
                        contentDescription = "Profile photo crop",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.size(imageWidth, imageHeight).graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = offsetX * maxPanX
                            translationY = offsetY * maxPanY
                            rotationZ = rotation.toFloat()
                        },
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.72f),
                            radius = size.minDimension / 2f - 4.dp.toPx(),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                    if (saving) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }
            if (animated) Text(
                "Animated photos use a centered crop.",
                color = MHTalkMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { rotation = (rotation + 90) % 360; offsetX = 0f; offsetY = 0f },
                    enabled = !saving,
                ) { Icon(Icons.Rounded.RotateRight, null); Spacer(Modifier.width(6.dp)); Text("Rotate") }
                OutlinedButton(
                    onClick = { zoom = 1f; offsetX = 0f; offsetY = 0f; rotation = 0 },
                    enabled = !saving,
                ) { Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("Reset") }
                Button(
                    onClick = { onUse(zoom, offsetX, offsetY, rotation) },
                    enabled = !saving,
                ) { Text("Next") }
            }
        }
    }
}
