package com.mhlko.talk.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mhlko.talk.data.SubscriptionTier

@Composable
internal fun MembershipBadge(tier: SubscriptionTier, modifier: Modifier = Modifier) {
    if (tier == SubscriptionTier.Free) return
    val color = when (tier) {
        SubscriptionTier.Plus -> Color(0xFFB9B0FF)
        SubscriptionTier.Pro -> Color(0xFF74EFB8)
        SubscriptionTier.Ultimate -> Color(0xFFDFA2FF)
        SubscriptionTier.MaxSupporter -> Color(0xFFFFDC67)
        SubscriptionTier.Free -> MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.42f)),
    ) {
        Text(
            tier.displayName.uppercase(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = color,
            fontSize = 8.sp,
            fontWeight = FontWeight.Black,
        )
    }
}
