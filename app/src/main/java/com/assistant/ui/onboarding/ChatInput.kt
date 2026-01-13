package com.assistant.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Chat input component at the bottom of the screen.
 * 
 * Design:
 * - Single text input with send button
 * - Dark background, subtle styling
 * - No default Material buttons (custom send icon)
 */
@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1F))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp, max = 120.dp),
            enabled = enabled,
            placeholder = {
                androidx.compose.material3.Text(
                    text = "Type your reply...",
                    color = Color(0xFF6B6B75),
                    fontSize = 16.sp
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF2D2D35),
                unfocusedContainerColor = Color(0xFF2D2D35),
                disabledContainerColor = Color(0xFF2D2D35),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = Color(0xFF8B8B95)
            ),
            shape = RoundedCornerShape(24.dp),
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { onSend() }
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color(0xFFE8E8E8),
                fontSize = 16.sp
            )
        )
        
        IconButton(
            onClick = onSend,
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = if (text.isNotBlank()) Color(0xFF4A4A55) else Color(0xFF2D2D35),
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank()) Color(0xFFE8E8E8) else Color(0xFF6B6B75),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

