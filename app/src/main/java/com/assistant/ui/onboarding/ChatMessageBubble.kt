package com.assistant.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WhatsApp-style chat message bubble with avatar.
 * 
 * Design:
 * - Assistant messages on left with circular avatar
 * - User messages on right (no avatar)
 * - Asymmetrical rounded corners (WhatsApp-style)
 * - Soft, calm colors for friendly tone
 * - Dark-first design
 */
@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isAssistant = message.isFromAssistant
    
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(250)) + 
                slideInHorizontally(
                    initialOffsetX = { if (isAssistant) -it / 2 else it / 2 },
                    animationSpec = tween(350)
                ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = if (isAssistant) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.Bottom
        ) {
            if (isAssistant) {
                // Assistant avatar (circular, friendly and warm)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            // Warm, friendly teal-blue color
                            Color(0xFF4A9E9E)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "😊",
                        fontSize = 22.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            // Message bubble
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(
                        // WhatsApp-style: asymmetrical corners
                        RoundedCornerShape(
                            topStart = if (isAssistant) 4.dp else 18.dp,
                            topEnd = if (isAssistant) 18.dp else 4.dp,
                            bottomStart = if (isAssistant) 18.dp else 18.dp,
                            bottomEnd = if (isAssistant) 18.dp else 18.dp
                        )
                    )
                    .background(
                        if (isAssistant) {
                            // Assistant bubble - soft gray-green (WhatsApp-like)
                            Color(0xFF2B2B35)
                        } else {
                            // User bubble - gentle blue accent
                            Color(0xFF1E3A5F)
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = if (isAssistant) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Text(
                    text = message.text,
                    color = Color(0xFFE8E8E8),
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    textAlign = if (isAssistant) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            if (!isAssistant) {
                Spacer(modifier = Modifier.width(40.dp)) // Space for alignment
            }
        }
    }
}
