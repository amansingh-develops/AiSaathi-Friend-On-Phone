package com.assistant.ui.screen
 
 import androidx.compose.animation.core.*
 import androidx.compose.foundation.background
 import androidx.compose.foundation.clickable
 import androidx.compose.foundation.interaction.MutableInteractionSource
 import androidx.compose.foundation.interaction.collectIsPressedAsState
 import androidx.compose.foundation.layout.*
 import androidx.compose.foundation.layout.statusBars
 import androidx.compose.foundation.layout.systemBars
 import androidx.compose.foundation.shape.CircleShape
 import androidx.compose.foundation.shape.RoundedCornerShape
 import androidx.compose.material.icons.Icons
 import androidx.compose.material.icons.rounded.Settings
 import androidx.compose.material3.Icon
 import androidx.compose.material3.Text
 import androidx.compose.runtime.*
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
 import androidx.compose.ui.draw.alpha
 import androidx.compose.ui.draw.clip
 import androidx.compose.ui.draw.drawBehind
 import androidx.compose.ui.draw.scale
 import androidx.compose.ui.geometry.Offset
 import androidx.compose.ui.graphics.Brush
 import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.graphics.Shadow
 import androidx.compose.ui.graphics.TileMode
 import androidx.compose.ui.platform.LocalContext
 import androidx.compose.ui.text.TextStyle
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.unit.sp
 import androidx.lifecycle.viewmodel.compose.viewModel
 import com.assistant.domain.model.UserProfile
 import com.assistant.viewmodel.WakeWordViewModel
 
 @Composable
 fun HomeScreen(
     userProfile: UserProfile,
     onOpenSettings: () -> Unit
 ) {
     val context = LocalContext.current
     val viewModel: WakeWordViewModel = viewModel()
     
     // Register broadcast receiver when screen is shown
     LaunchedEffect(Unit) {
         viewModel.registerReceiver(context)
     }
     
     // Unregister when screen is disposed
     DisposableEffect(Unit) {
         onDispose {
             viewModel.unregisterReceiver(context)
         }
     }
     
     // Observe wake word detection state
     val isWakeWordDetected by viewModel.isWakeWordDetected.collectAsState()
     
     // Animate AI Orb pulse
     val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
     val pulseScale by infiniteTransition.animateFloat(
         initialValue = 1.0f,
         targetValue = if (isWakeWordDetected) 1.2f else 1.05f,
         animationSpec = infiniteRepeatable(
             animation = tween(if (isWakeWordDetected) 400 else 2000, easing = FastOutSlowInEasing),
             repeatMode = RepeatMode.Reverse
         ),
         label = "pulse_scale"
     )
 
     // Soft press feedback for the settings icon
     val settingsPressed = remember { MutableInteractionSource() }
     val settingsIsPressed by settingsPressed.collectIsPressedAsState()
     val settingsScale by animateFloatAsState(
         targetValue = if (settingsIsPressed) 0.92f else 1f,
         animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f),
         label = "settings_scale"
     )
 
     val accentColor = Color(0xFF35D3FF) // electric cyan
     val secondaryAccent = Color(0xFF8B78FF) // violet
     val bgColor = Color(0xFF070A10)
     
     Box(
         modifier = Modifier
             .fillMaxSize()
             .background(bgColor)
             .drawBehind {
                 // Aurora glow background
                 val r = size.minDimension * 0.8f
                 drawRect(
                     brush = Brush.radialGradient(
                         colors = listOf(accentColor.copy(alpha = 0.12f), Color.Transparent),
                         center = Offset(size.width * 0.8f, size.height * 0.2f),
                         radius = r
                     )
                 )
                 drawRect(
                     brush = Brush.radialGradient(
                         colors = listOf(secondaryAccent.copy(alpha = 0.08f), Color.Transparent),
                         center = Offset(size.width * 0.2f, size.height * 0.7f),
                         radius = r
                     )
                 )
             }
             .padding(24.dp),
         contentAlignment = Alignment.Center
     ) {
         // Minimal top-right settings button
         Box(
             modifier = Modifier
                 .align(Alignment.TopEnd)
                 .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                 .padding(top = 10.dp, end = 18.dp) // Offset from top-right corner
                 .size(44.dp)
                 .scale(settingsScale)
                 .clip(RoundedCornerShape(14.dp))
                 .background(Color(0xFF161B26))
                 .drawBehind {
                     drawRoundRect(
                         brush = Brush.linearGradient(
                             colors = listOf(accentColor.copy(alpha = 0.3f), Color.Transparent)
                         ),
                         cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                         style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                     )
                 }
                 .clickable(
                     interactionSource = settingsPressed,
                     indication = null,
                     onClick = onOpenSettings
                 ),
             contentAlignment = Alignment.Center
         ) {
             Icon(
                 imageVector = Icons.Rounded.Settings,
                 contentDescription = "Open settings",
                 tint = Color(0xFFEAF0FF),
                 modifier = Modifier.size(20.dp)
             )
         }
 
         Column(
             horizontalAlignment = Alignment.CenterHorizontally,
             verticalArrangement = Arrangement.spacedBy(48.dp)
         ) {
             // Hero Welcome Text
             Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                 Text(
                     text = "Welcome, ${userProfile.preferredName ?: userProfile.userName ?: "Friend"}! 👋",
                     color = Color(0xFFEAF0FF),
                     fontSize = 28.sp,
                     fontWeight = FontWeight.Bold,
                     letterSpacing = (-0.5).sp,
                     style = TextStyle(
                         shadow = Shadow(
                             color = accentColor.copy(alpha = 0.2f),
                             blurRadius = 12f
                         )
                     )
                 )
                 Text(
                     text = "Your AI is listening",
                     color = Color(0xFF9AA7C2),
                     fontSize = 14.sp,
                     fontWeight = FontWeight.Medium,
                     letterSpacing = 0.5.sp
                 )
             }
             
             // Futuristic Glowing AI Orb
             Box(
                 modifier = Modifier
                     .size(200.dp)
                     .scale(pulseScale),
                 contentAlignment = Alignment.Center
             ) {
                 // Outer Bloom
                 Box(
                     modifier = Modifier
                         .fillMaxSize()
                         .drawBehind {
                             drawCircle(
                                 brush = Brush.radialGradient(
                                     colors = listOf(
                                         if (isWakeWordDetected) accentColor.copy(alpha = 0.4f) else accentColor.copy(alpha = 0.15f),
                                         Color.Transparent
                                     )
                                 ),
                                 radius = size.minDimension / 1.5f
                             )
                         }
                 )
                 
                 // Core Orb
                 Box(
                     modifier = Modifier
                         .size(100.dp)
                         .clip(CircleShape)
                         .background(
                             brush = Brush.linearGradient(
                                 colors = if (isWakeWordDetected) {
                                     listOf(accentColor, Color(0xFF2A79FF))
                                 } else {
                                     listOf(Color(0xFF1C2533), Color(0xFF0D1219))
                                 }
                             )
                         )
                         .drawBehind {
                             val strokeWidth = 2.dp.toPx()
                             drawCircle(
                                 brush = Brush.sweepGradient(
                                     colors = listOf(accentColor.copy(alpha = 0.8f), Color.Transparent, accentColor.copy(alpha = 0.8f))
                                 ),
                                 style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                             )
                             // Inner highlight
                             drawCircle(
                                 brush = Brush.radialGradient(
                                     colors = listOf(Color.White.copy(alpha = 0.2f), Color.Transparent),
                                     center = Offset(size.width * 0.3f, size.height * 0.3f),
                                     radius = size.minDimension / 2.5f
                                 )
                             )
                         },
                     contentAlignment = Alignment.Center
                 ) {
                     if (isWakeWordDetected) {
                         // Active state: pulsing dot
                         Box(
                             modifier = Modifier
                                 .size(12.dp)
                                 .clip(CircleShape)
                                 .background(Color.White)
                                 .drawBehind {
                                     drawCircle(
                                         color = Color.White.copy(alpha = 0.5f),
                                         radius = size.minDimension * 2f
                                     )
                                 }
                         )
                     } else {
                         // Idle state: subtle ring
                         Box(
                             modifier = Modifier
                                 .size(32.dp)
                                 .drawBehind {
                                     drawCircle(
                                         color = accentColor.copy(alpha = 0.3f),
                                         style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                                     )
                                 }
                         )
                     }
                 }
             }
             
             // Dynamic Status Text
             Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                 Text(
                     text = if (isWakeWordDetected) "I'm listening..." else "Say \"${userProfile.wakeWord ?: "hey aman"}\"",
                     color = if (isWakeWordDetected) accentColor else Color(0xFF9AA7C2),
                     fontSize = 18.sp,
                     fontWeight = FontWeight.SemiBold,
                     letterSpacing = 0.2.sp
                 )
                 
                 // Assistant name capsule
                 Box(
                     modifier = Modifier
                         .clip(RoundedCornerShape(999.dp))
                         .background(Color(0xFF161B26))
                         .padding(horizontal = 16.dp, vertical = 6.dp)
                         .drawBehind {
                             drawRoundRect(
                                 color = Color(0x1AFFFFFF),
                                 cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height/2, size.height/2),
                                 style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                             )
                         }
                 ) {
                     Text(
                         text = "${userProfile.assistantName ?: "Assistant"} is ready",
                         color = Color(0xFF9AA7C2).copy(alpha = 0.8f),
                         fontSize = 12.sp,
                         fontWeight = FontWeight.Medium
                     )
                 }
             }
         }
     }
 }
