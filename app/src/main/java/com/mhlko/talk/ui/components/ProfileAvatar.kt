package com.mhlko.talk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mhlko.talk.data.isImageAvatar
import com.mhlko.talk.ui.theme.MHTalkPurple

@Composable
fun ProfileAvatar(
    avatar: String?,
    name: String,
    modifier: Modifier,
    shape: Shape,
    background: Color = MHTalkPurple,
    fontSize: TextUnit = 18.sp,
) {
    val value = avatar.orEmpty().trim()
    val initials = value.takeUnless(::isImageAvatar)
        ?.take(2)
        ?.takeIf(String::isNotBlank)
        ?: name.trim().take(1).ifBlank { "M" }
    Box(
        modifier = modifier.clip(shape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            initials.uppercase(),
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
        )
        if (isImageAvatar(value)) {
            AsyncImage(
                model = value,
                contentDescription = "$name profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
