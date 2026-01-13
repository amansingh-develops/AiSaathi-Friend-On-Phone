package com.assistant.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlaybackAppOption(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "scale")
    
    val bg = if (selected) accent.copy(alpha = 0.2f) else Color(0xFF0C101A) // panel color
    val border = if (selected) accent else Color(0x26FFFFFF) // strokeSoft
    val contentColor = if (selected) accent else Color(0xFFEAF0FF) // onBg

    Box(
        modifier = modifier
            .scale(scale)
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = contentColor,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp
        )
    }
}

// Since PrimaryGlowButton was used in my snippet but not defined in the original file view (it might be further down),
// I'll assume it exists or I need to define it if I can't find it.
// Looking at the view_file output from Step 139, I saw usage of `PrimaryGlowButton` at line 289.
// So it must be defined in the file (probably lines 800+ which I didn't see).
// I won't redefine it.
