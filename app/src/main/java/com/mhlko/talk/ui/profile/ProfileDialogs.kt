package com.mhlko.talk.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.mhlko.talk.data.AvatarCropSelection
import com.mhlko.talk.data.UserProfile
import com.mhlko.talk.ui.components.ProfileAvatar
import com.mhlko.talk.ui.theme.MHTalkMuted
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
                ProfileAvatar(
                    avatar = profile.avatar,
                    name = name,
                    modifier = Modifier.size(92.dp),
                    shape = CircleShape,
                    fontSize = 28.sp,
                )
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
    onUse: (AvatarCropSelection) -> Unit,
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

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF08090C)).statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column {
                    Text("Crop profile photo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Move and scale the photo", color = MHTalkMuted, fontSize = 12.sp)
                }
            }
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).clipToBounds().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val cropSize = minOf(maxWidth - 40.dp, maxHeight - 48.dp).coerceAtLeast(160.dp)
                val cropPx = with(density) { cropSize.toPx() }
                val rotatedWidth = if (rotation % 180 == 0) dimensions.first else dimensions.second
                val rotatedHeight = if (rotation % 180 == 0) dimensions.second else dimensions.first
                val baseScale = maxOf(cropPx / rotatedWidth, cropPx / rotatedHeight)
                val imageWidth = with(density) { (dimensions.first * baseScale).toDp() }
                val imageHeight = with(density) { (dimensions.second * baseScale).toDp() }
                val maxPanX = ((rotatedWidth * baseScale * zoom - cropPx) / 2f).coerceAtLeast(0f)
                val maxPanY = ((rotatedHeight * baseScale * zoom - cropPx) / 2f).coerceAtLeast(0f)
                val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
                    if (!animated && !saving) {
                        zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                        if (maxPanX > 0f) offsetX = (offsetX + panChange.x / maxPanX).coerceIn(-1f, 1f)
                        if (maxPanY > 0f) offsetY = (offsetY + panChange.y / maxPanY).coerceIn(-1f, 1f)
                    }
                }
                Box(
                    Modifier.fillMaxSize().transformable(transformState),
                    contentAlignment = Alignment.Center,
                ) {
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
                        val left = (size.width - cropPx) / 2f
                        val top = (size.height - cropPx) / 2f
                        val right = left + cropPx
                        val bottom = top + cropPx
                        val shade = Color.Black.copy(alpha = 0.64f)
                        drawRect(shade, size = Size(size.width, top.coerceAtLeast(0f)))
                        drawRect(shade, topLeft = Offset(0f, bottom), size = Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
                        drawRect(shade, topLeft = Offset(0f, top), size = Size(left.coerceAtLeast(0f), cropPx))
                        drawRect(shade, topLeft = Offset(right, top), size = Size((size.width - right).coerceAtLeast(0f), cropPx))
                        drawRect(
                            color = Color.White.copy(alpha = 0.78f),
                            topLeft = Offset(left, top),
                            size = Size(cropPx, cropPx),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                        val corner = 24.dp.toPx()
                        val stroke = 3.dp.toPx()
                        listOf(
                            Offset(left, top) to Pair(1f, 1f),
                            Offset(right, top) to Pair(-1f, 1f),
                            Offset(left, bottom) to Pair(1f, -1f),
                            Offset(right, bottom) to Pair(-1f, -1f),
                        ).forEach { (origin, direction) ->
                            drawLine(Color.White, origin, Offset(origin.x + corner * direction.first, origin.y), stroke)
                            drawLine(Color.White, origin, Offset(origin.x, origin.y + corner * direction.second), stroke)
                        }
                    }
                    if (saving) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(Modifier.height(12.dp))
                                Text("Updating profile photo…", color = Color.White)
                            }
                        }
                    }
                }
            }
            if (!animated) Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) }, enabled = !saving && zoom > 1f) {
                    Icon(Icons.Rounded.ZoomOut, "Zoom out")
                }
                Slider(
                    value = zoom,
                    onValueChange = { zoom = it },
                    valueRange = 1f..4f,
                    enabled = !saving,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(4f) }, enabled = !saving && zoom < 4f) {
                    Icon(Icons.Rounded.ZoomIn, "Zoom in")
                }
            } else Text(
                "Animated photos keep their animation and use a centered square preview.",
                color = MHTalkMuted,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { zoom = 1f; offsetX = 0f; offsetY = 0f; rotation = 0 },
                    enabled = !saving && !animated,
                ) { Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(5.dp)); Text("Reset") }
                TextButton(
                    onClick = { rotation = (rotation + 90) % 360; zoom = 1f; offsetX = 0f; offsetY = 0f },
                    enabled = !saving && !animated,
                ) { Icon(Icons.AutoMirrored.Rounded.RotateRight, null); Spacer(Modifier.width(5.dp)); Text("Rotate") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onUse(AvatarCropSelection(zoom, offsetX, offsetY, rotation)) },
                    enabled = !saving,
                ) { Text("Use photo") }
            }
        }
    }
}
