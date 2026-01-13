package com.assistant.ui.permissions
 
 import android.Manifest
 import android.os.Build
 import androidx.activity.compose.rememberLauncherForActivityResult
 import androidx.activity.result.contract.ActivityResultContracts
 import androidx.compose.animation.core.*
 import androidx.compose.foundation.background
 import androidx.compose.foundation.clickable
 import androidx.compose.foundation.interaction.MutableInteractionSource
 import androidx.compose.foundation.interaction.collectIsPressedAsState
 import androidx.compose.foundation.layout.*
 import androidx.compose.foundation.shape.CircleShape
 import androidx.compose.foundation.shape.RoundedCornerShape
 import androidx.compose.material3.Text
 import androidx.compose.runtime.*
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
 import androidx.compose.ui.draw.clip
 import androidx.compose.ui.draw.drawBehind
 import androidx.compose.ui.draw.scale
 import androidx.compose.ui.geometry.Offset
 import androidx.compose.ui.graphics.Brush
 import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.platform.LocalContext
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.text.style.TextAlign
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.unit.sp
 import androidx.core.content.ContextCompat
 
 /**
  * Permission request screen shown after onboarding.
  * 
  * Requests all required permissions for the assistant with a 2025 tech aesthetic.
  */
 @Composable
 fun PermissionScreen(
     onAllPermissionsGranted: () -> Unit
 ) {
     val context = LocalContext.current
     var allGranted by remember { mutableStateOf(false) }
     
     // Visual tokens
     val accentColor = Color(0xFF35D3FF)
     val panelColor = Color(0xFF111722)
 
     // Check current permissions
     val audioPermission = remember {
         ContextCompat.checkSelfPermission(
             context,
             Manifest.permission.RECORD_AUDIO
         ) == android.content.pm.PackageManager.PERMISSION_GRANTED
     }
     
     val contactsPermission = remember {
         ContextCompat.checkSelfPermission(
             context,
             Manifest.permission.READ_CONTACTS
         ) == android.content.pm.PackageManager.PERMISSION_GRANTED
     }
     
     val permissionLauncher = rememberLauncherForActivityResult(
         contract = ActivityResultContracts.RequestMultiplePermissions()
     ) { permissions ->
         val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
         val contactsGranted = permissions[Manifest.permission.READ_CONTACTS] == true
         val callGranted = permissions[Manifest.permission.CALL_PHONE] == true
         
         // We consider it "granted" if core Audio + Call + Contacts are allowed (or best effort).
         // Ideally for "Full Control" we strictly want all, but Audio is the only blocker for the *app* starting.
         // We'll update the state to reflect all needed.
         allGranted = audioGranted
         if (allGranted) {
             onAllPermissionsGranted()
         }
     }
     
     LaunchedEffect(Unit) {
         val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
         if (Build.VERSION.SDK_INT >= 33) {
             perms.add(Manifest.permission.POST_NOTIFICATIONS)
         }
         
         val allHave = perms.all {
             ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
         }

         if (allHave) {
             allGranted = true
             onAllPermissionsGranted()
         }
     }
     
     LaunchedEffect(Unit) {
         if (audioPermission && contactsPermission) {
             allGranted = true
             onAllPermissionsGranted()
         }
     }
     
     // Button press interaction
     val interactionSource = remember { MutableInteractionSource() }
     val isPressed by interactionSource.collectIsPressedAsState()
     val buttonScale by animateFloatAsState(
         targetValue = if (isPressed) 0.96f else 1f,
         animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
         label = "btn_scale"
     )
 
     Box(
         modifier = Modifier
             .fillMaxSize()
             .background(Color.Black.copy(alpha = 0.85f)) // Deep overlay
             .drawBehind {
                 // Background glow
                 drawRect(
                     brush = Brush.radialGradient(
                         colors = listOf(accentColor.copy(alpha = 0.1f), Color.Transparent),
                         center = Offset(size.width * 0.5f, size.height * 0.4f),
                         radius = size.minDimension
                     )
                 )
             },
         contentAlignment = Alignment.Center
     ) {
         // Glass Card
         Column(
             horizontalAlignment = Alignment.CenterHorizontally,
             verticalArrangement = Arrangement.spacedBy(32.dp),
             modifier = Modifier
                 .fillMaxWidth()
                 .padding(24.dp)
                 .clip(RoundedCornerShape(32.dp))
                 .background(panelColor.copy(alpha = 0.9f))
                 .drawBehind {
                     // Glass border
                     drawRoundRect(
                         brush = Brush.linearGradient(
                             colors = listOf(Color.White.copy(alpha = 0.2f), Color.Transparent)
                         ),
                         cornerRadius = androidx.compose.ui.geometry.CornerRadius(32.dp.toPx(), 32.dp.toPx()),
                         style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                     )
                 }
                 .padding(32.dp)
         ) {
             // Glowing Icon Orb
             Box(
                 modifier = Modifier
                     .size(120.dp),
                 contentAlignment = Alignment.Center
             ) {
                 Box(
                     modifier = Modifier
                         .fillMaxSize()
                         .drawBehind {
                             drawCircle(
                                 brush = Brush.radialGradient(
                                     colors = listOf(accentColor.copy(alpha = 0.2f), Color.Transparent)
                                 ),
                                 radius = size.minDimension / 1.2f
                             )
                         }
                 )
                 Box(
                     modifier = Modifier
                         .size(72.dp)
                         .clip(CircleShape)
                         .background(accentColor.copy(alpha = 0.1f))
                         .drawBehind {
                             drawCircle(
                                 color = accentColor,
                                 style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                             )
                         },
                     contentAlignment = Alignment.Center
                 ) {
                     Text(
                         text = "🎤",
                         fontSize = 32.sp
                     )
                 }
             }
             
             // Text Content
             Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                 Text(
                     text = "Access Required",
                     color = Color.White,
                     fontSize = 28.sp,
                     fontWeight = FontWeight.Bold,
                     textAlign = TextAlign.Center
                 )
                 Text(
                     text = "To bring your assistant to life, we need access to the microphone (for listening) and contacts (for calling).",
                     color = Color(0xFF9AA7C2),
                     fontSize = 16.sp,
                     textAlign = TextAlign.Center,
                     lineHeight = 24.sp
                 )
             }
             
             // Tech-style instruction
             Box(
                 modifier = Modifier
                     .fillMaxWidth()
                     .clip(RoundedCornerShape(16.dp))
                     .background(Color(0xFF161B26))
                     .padding(16.dp)
             ) {
                 Text(
                     text = "IMPORTANT: Select \"While using the app\" for Microphone. Grant Contacts permission for calling features.",
                     color = accentColor.copy(alpha = 0.9f),
                     fontSize = 13.sp,
                     fontWeight = FontWeight.Medium,
                     textAlign = TextAlign.Center,
                     lineHeight = 20.sp
                 )
             }
             
             // Action Button
             Box(
                 modifier = Modifier
                     .fillMaxWidth()
                     .height(56.dp)
                     .scale(buttonScale)
                     .clip(RoundedCornerShape(16.dp))
                     .background(
                         brush = Brush.horizontalGradient(
                             colors = listOf(accentColor, Color(0xFF2A79FF))
                         )
                     )
                     .clickable(
                         interactionSource = interactionSource,
                         indication = null,
                         onClick = {
                            val perms = mutableListOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.CALL_PHONE
                            )
                            if (Build.VERSION.SDK_INT >= 33) {
                                perms.add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permissionLauncher.launch(perms.toTypedArray())
                        }
                    ),
                 contentAlignment = Alignment.Center
             ) {
                 Text(
                     text = "ENABLE ACCESS",
                     color = Color(0xFF070A10),
                     fontSize = 14.sp,
                     fontWeight = FontWeight.Bold,
                     letterSpacing = 1.sp
                 )
             }
         }
     }
 }
