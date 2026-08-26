package com.mhlko.talk.data

import kotlin.math.floor

data class AvatarCropSelection(
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Int = 0,
)

data class AvatarCropRect(
    val left: Int,
    val top: Int,
    val side: Int,
)

fun calculateAvatarCrop(
    width: Int,
    height: Int,
    selection: AvatarCropSelection,
): AvatarCropRect {
    require(width > 0 && height > 0) { "Image dimensions must be positive" }
    val zoom = selection.zoom.coerceIn(1f, 4f)
    val side = floor(minOf(width, height) / zoom).toInt().coerceIn(1, minOf(width, height))
    val horizontalTravel = (width - side) / 2f
    val verticalTravel = (height - side) / 2f
    val centerX = width / 2f - selection.offsetX.coerceIn(-1f, 1f) * horizontalTravel
    val centerY = height / 2f - selection.offsetY.coerceIn(-1f, 1f) * verticalTravel
    return AvatarCropRect(
        left = (centerX - side / 2f).toInt().coerceIn(0, width - side),
        top = (centerY - side / 2f).toInt().coerceIn(0, height - side),
        side = side,
    )
}
