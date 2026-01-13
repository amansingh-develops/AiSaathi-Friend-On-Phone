package com.assistant.ui.welcome
 
 import androidx.compose.animation.core.*
 import androidx.compose.foundation.background
 import androidx.compose.foundation.clickable
 import androidx.compose.foundation.layout.*
 import androidx.compose.foundation.shape.RoundedCornerShape
 import androidx.compose.material3.Text
 import androidx.compose.runtime.*
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
 import androidx.compose.ui.draw.alpha
 import androidx.compose.ui.draw.drawBehind
 import androidx.compose.ui.draw.scale
 import androidx.compose.ui.geometry.Offset
 import androidx.compose.ui.graphics.Brush
 import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.graphics.Shadow
 import androidx.compose.ui.text.TextStyle
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.unit.sp
 import com.assistant.domain.model.UserProfile
 import kotlinx.coroutines.delay
 
 /**
  * Welcome screen shown after onboarding completes.
  * 
  * Shows a brief welcome animation with the user's name and assistant name,
  * then navigates to the home screen automatically.
  */
 @Composable
 fun WelcomeScreen(
     userProfile: UserProfile,
     onAnimationComplete: () -> Unit
 ) {
     var showContent by remember { mutableStateOf(false) }
     
     // Visual tokens
     val accentColor = Color(0xFF35D3FF)
     val bgColor = Color(0xFF070A10)
 
     // Animate content appearance
     val alpha by animateFloatAsState(
         targetValue = if (showContent) 1f else 0f,
         animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
         label = "welcome_alpha"
     )
     
     val scale by animateFloatAsState(
         targetValue = if (showContent) 1f else 0.9f,
         animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
         label = "welcome_scale"
     )
     
     // Start animation and auto-navigate after delay
     LaunchedEffect(Unit) {
         delay(400)
         showContent = true
         
         // Auto-navigate after giving user time to feel the vibe
         delay(3500)
         onAnimationComplete()
     }
     
     Box(
         modifier = Modifier
             .fillMaxSize()
             .background(bgColor)
             .drawBehind {
                 // Background tech-aurora
                 val r = size.minDimension * 0.9f
                 drawRect(
                     brush = Brush.radialGradient(
                         colors = listOf(accentColor.copy(alpha = 0.15f), Color.Transparent),
                         center = Offset(size.width * 0.7f, size.height * 0.2f),
                         radius = r
                     )
                 )
             },
         contentAlignment = Alignment.Center
     ) {
         Column(
             horizontalAlignment = Alignment.CenterHorizontally,
             verticalArrangement = Arrangement.spacedBy(16.dp),
             modifier = Modifier
                 .alpha(alpha)
                 .scale(scale)
                 .padding(32.dp)
         ) {
             // Hero Greeting
             Text(
                 text = "Hey ${userProfile.preferredName ?: userProfile.userName ?: "there"}! 👋",
                 color = Color(0xFFEAF0FF),
                 fontSize = 36.sp,
                 fontWeight = FontWeight.Bold,
                 letterSpacing = (-1).sp,
                 style = TextStyle(
                     shadow = Shadow(
                         color = accentColor.copy(alpha = 0.3f),
                         blurRadius = 20f
                     )
                 )
             )
             
             // Assistant introduction
             Text(
                 text = "${userProfile.assistantName ?: "Your assistant"} is ready to help.",
                 color = Color(0xFF9AA7C2),
                 fontSize = 18.sp,
                 fontWeight = FontWeight.Medium,
                 textAlign = androidx.compose.ui.text.style.TextAlign.Center
             )
             
             Spacer(Modifier.height(24.dp))
             
             // Subtle "initializing" hint
             Row(
                 verticalAlignment = Alignment.CenterVertically,
                 horizontalArrangement = Arrangement.spacedBy(8.dp),
                 modifier = Modifier.alpha(0.6f)
             ) {
                 Box(
                     modifier = Modifier
                         .size(8.dp)
                         .background(accentColor, shape = RoundedCornerShape(999.dp))
                 )
                 Text(
                     text = "CONNECTED",
                     color = accentColor,
                     fontSize = 10.sp,
                     fontWeight = FontWeight.Bold,
                     letterSpacing = 2.sp
                 )
             }
         }
 
         // Optional "Continue" overlay if user wants to skip
         Box(
             modifier = Modifier
                 .align(Alignment.BottomCenter)
                 .padding(bottom = 48.dp)
                 .alpha(alpha * 0.5f)
                 .clickable { onAnimationComplete() }
                 .padding(16.dp)
         ) {
             Text(
                 text = "TAP TO SKIP",
                 color = Color.White,
                 fontSize = 11.sp,
                 fontWeight = FontWeight.Bold,
                 letterSpacing = 1.sp
             )
         }
     }
 }
