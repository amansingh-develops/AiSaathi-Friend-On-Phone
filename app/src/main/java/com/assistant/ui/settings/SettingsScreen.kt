package com.assistant.ui.settings

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.domain.model.UserProfile
import com.assistant.domain.onboarding.OnboardingLanguage
import com.assistant.domain.UserPreferences
import com.assistant.domain.PlaybackApp
import com.assistant.domain.ConfirmationStyle

/**
 * Trendy, tech-forward Settings UI (2025 vibe):
 * - Dark-first, layered depth (subtle gradients + floating panels)
 * - Glass top bar (blur on API 31+, translucent fallback otherwise)
 * - “Glow” selection states (tasteful neon accent, not flashy)
 * - Springy micro-interactions (soft press scale + eased transitions)
 *
 * NOTE: This composable is deliberately parameter-driven.
 * It does not persist anything; callers decide what “save” means.
 */
@Composable
fun SettingsScreen(
    userProfile: UserProfile,
    userPreferences: UserPreferences,
    selectedVoicePersona: VoicePersona,
    onBack: () -> Unit,
    onSave: (UserProfile, UserPreferences, VoicePersona) -> Unit, // SINGLE SAVE CALLBACK
    modifier: Modifier = Modifier
) {
    val t = remember { SettingsTokens() }
    val scroll = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // --- DRAFT STATE (Local copies of settings) ---
    var draftProfile by remember(userProfile) { mutableStateOf(userProfile) }
    var draftPreferences by remember(userPreferences) { mutableStateOf(userPreferences) }
    var draftPersona by remember(selectedVoicePersona) { mutableStateOf(selectedVoicePersona) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(t.bg)
            .drawBehind {
                // Background atmosphere: a near-black base with a subtle “tech aurora” glow.
                val r = size.minDimension * 0.72f
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(t.accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.78f, size.height * 0.18f),
                        radius = r
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(t.accent2.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.18f, size.height * 0.65f),
                        radius = r * 0.9f
                    )
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopBar(
                title = "Settings",
                onBack = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Column(
                modifier = Modifier
                    .weight(1f) // Take remaining space (Push button to bottom)
                    .verticalScroll(scroll)
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(6.dp))

                FloatingSectionCard(
                    title = "Assistant profile",
                    subtitle = "Make it feel personal — without feeling “settings-y”.",
                    modifier = Modifier.fillMaxWidth(),
                    accent = t.accent
                ) {
                    val assistantName = draftProfile.assistantName?.takeIf { it.isNotBlank() } ?: "Assistant"
                    Text(
                        text = assistantName,
                        color = t.onBg,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.4).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            shadow = Shadow(
                                color = t.accent.copy(alpha = 0.30f),
                                blurRadius = 22f,
                                offset = Offset(0f, 0f)
                            )
                        )
                    )

                    Spacer(Modifier.height(10.dp))

                    InlineEditableField(
                        label = "You",
                        value = draftProfile.preferredName ?: draftProfile.userName.orEmpty(),
                        placeholder = "Your name",
                        accent = t.accent,
                        onCommit = { newName ->
                            draftProfile = draftProfile.update(preferredName = newName.trim().ifBlank { null })
                        }
                    )

                    Spacer(Modifier.height(10.dp))

                    InlineEditableField(
                        label = "Assistant",
                        value = draftProfile.assistantName.orEmpty(),
                        placeholder = "Assistant name",
                        accent = t.accent,
                        onCommit = { newName ->
                            draftProfile = draftProfile.update(assistantName = newName.trim().ifBlank { null })
                        }
                    )
                }

                FloatingSectionCard(
                    title = "Voice personality",
                    subtitle = "A vibe choice — calm, clear, or a little bold.",
                    modifier = Modifier.fillMaxWidth(),
                    accent = t.accent2
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VoicePersonaCard(
                            persona = VoicePersona.Feminine,
                            selected = draftPersona == VoicePersona.Feminine,
                            accent = t.accent,
                            onSelect = { draftPersona = VoicePersona.Feminine }
                        )
                        VoicePersonaCard(
                            persona = VoicePersona.Masculine,
                            selected = draftPersona == VoicePersona.Masculine,
                            accent = t.accent,
                            onSelect = { draftPersona = VoicePersona.Masculine }
                        )
                    }
                }

                FloatingSectionCard(
                    title = "Language",
                    subtitle = "Choose how I speak back — Hindi, Hinglish, or English.",
                    modifier = Modifier.fillMaxWidth(),
                    accent = t.accent
                ) {
                    val current = remember(draftProfile.preferredLanguage) {
                        OnboardingLanguage.fromCode(draftProfile.preferredLanguage)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                         // Only updating draftProfile on select
                         val languages = listOf(OnboardingLanguage.ENGLISH, OnboardingLanguage.HINGLISH, OnboardingLanguage.HINDI)
                         languages.forEach { lang ->
                            LanguageChoiceCard(
                                language = lang,
                                selected = current == lang,
                                accent = t.accent,
                                onSelect = {
                                    draftProfile = draftProfile.update(preferredLanguage = lang.code)
                                }
                            )
                         }
                    }
                }

                FloatingSectionCard(
                    title = "Wake word",
                    subtitle = "The phrase that brings your assistant to life.",
                    modifier = Modifier.fillMaxWidth(),
                    accent = t.accent
                ) {
                    InlineEditableField(
                        label = "Phrase",
                        value = draftProfile.wakeWord?.takeIf { it.isNotBlank() } ?: "hey aman",
                        placeholder = "e.g. hey aman",
                        accent = t.accent,
                        onCommit = { newWord ->
                            draftProfile = draftProfile.update(wakeWord = newWord.trim().ifBlank { null })
                        }
                    )
                }

                 FloatingSectionCard(
                    title = "Media Preferences",
                    subtitle = "Control how I play music and videos.",
                    modifier = Modifier.fillMaxWidth(),
                    accent = t.accent
                ) {
                     // Music
                     Text("When I say 'play a song'...", color = t.onBgMuted, fontSize = 12.sp)
                     Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         listOf(PlaybackApp.SPOTIFY, PlaybackApp.YOUTUBE, PlaybackApp.ASK).forEach { app ->
                             PlaybackAppOption(
                                 label = app.name.replaceFirstChar { it.uppercase() },
                                 selected = draftPreferences.musicPlaybackApp == app,
                                 accent = t.accent,
                                 onClick = { draftPreferences = draftPreferences.copy(musicPlaybackApp = app) },
                                 modifier = Modifier.weight(1f)
                             )
                         }
                     }
                     
                     Spacer(Modifier.height(8.dp))
                     
                     // Video
                     Text("When I say 'watch a video'...", color = t.onBgMuted, fontSize = 12.sp)
                     Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                         listOf(PlaybackApp.YOUTUBE, PlaybackApp.ASK).forEach { app ->
                             PlaybackAppOption(
                                 label = app.name.replaceFirstChar { it.uppercase() },
                                 selected = draftPreferences.videoPlaybackApp == app,
                                 accent = t.accent,
                                 onClick = { draftPreferences = draftPreferences.copy(videoPlaybackApp = app) },
                                 modifier = Modifier.weight(1f)
                             )
                         }
                     }

                     Spacer(Modifier.height(8.dp))

                     Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                             Text("Auto-learn preferences", color = t.onBg, fontSize = 14.sp)
                             Text("Allow assistant to learn from choices", color = t.onBgMuted, fontSize = 12.sp)
                        }
                        PrimaryGlowButton(
                            text = if (draftPreferences.allowAutoLearning) "ON" else "OFF",
                            accent = if (draftPreferences.allowAutoLearning) t.accent else t.onBgMuted,
                            onClick = { 
                                draftPreferences = draftPreferences.copy(allowAutoLearning = !draftPreferences.allowAutoLearning) 
                            }
                        )
                    }
                }
                
                Spacer(Modifier.height(80.dp)) // Space for bottom button
            }
        }
        
        // --- SAVE BUTTON (Fixed Bottom) ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, t.bg.copy(alpha = 0.95f), t.bg)
                    )
                )
                .padding(16.dp)
        ) {
            PrimaryGlowButton(
                text = "Apply Settings",
                accent = t.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(
                        elevation = 16.dp, 
                        spotColor = t.accent, 
                        ambientColor = t.accent
                    ),
                onClick = {
                    onSave(draftProfile, draftPreferences, draftPersona)
                    android.widget.Toast.makeText(context, "Settings Applied Successfully", android.widget.Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Immutable
enum class VoicePersona(val label: String, val vibe: String) {
    Feminine(label = "Feminine", vibe = "Calm & warm"),
    Masculine(label = "Masculine", vibe = "Clear & friendly")
}

@Composable
private fun LanguageChoiceCard(
    language: OnboardingLanguage,
    selected: Boolean,
    accent: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = remember { SettingsTokens() }
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.9f),
        label = "lang_press_scale"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (selected) 0.65f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.92f),
        label = "lang_glow_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(shape)
            .background(t.panel.copy(alpha = 0.70f))
            .drawBehind {
                if (glowAlpha > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                accent.copy(alpha = 0.22f * glowAlpha),
                                Color.Transparent
                            )
                        )
                    )
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onSelect
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = language.displayName,
                    color = t.onBg,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = 0.1.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when (language) {
                        OnboardingLanguage.ENGLISH -> "Clear & crisp. Best for commands."
                        OnboardingLanguage.HINGLISH -> "Natural mix. Feels like home."
                        OnboardingLanguage.HINDI -> "Warm Hindi. Soft and personal."
                    },
                    color = t.onBgMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    (fadeIn() togetherWith fadeOut()).using(
                        SizeTransform(clip = false)
                    )
                },
                label = "lang_selected_anim"
            ) { isSelected ->
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Selected",
                            tint = accent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.size(28.dp))
                }
            }
        }
    }
}

/**
 * Visual tokens local to this screen (keeps it opinionated without requiring app-wide theme changes).
 */
@Immutable
private class SettingsTokens(
    val bg: Color = Color(0xFF070A10),          // near-black (not pure black)
    val panel: Color = Color(0xFF0C101A),       // floating surfaces
    val panel2: Color = Color(0xFF0A0D14),
    val onBg: Color = Color(0xFFEAF0FF),
    val onBgMuted: Color = Color(0xFF9AA7C2),
    val strokeSoft: Color = Color(0x26FFFFFF),
    val accent: Color = Color(0xFF35D3FF),      // electric cyan (primary)
    val accent2: Color = Color(0xFF8B78FF)      // violet (secondary)
)

@Composable
private fun GlassTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = remember { SettingsTokens() }
    val shape = RoundedCornerShape(20.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "back_press_scale"
    )

    Row(
        modifier = modifier
            .clip(shape)
            .then(glassModifier())
            .background(t.panel.copy(alpha = 0.75f)) // Slightly more opaque for better contrast
            .drawBehind {
                // Hairline glow at the bottom edge (subtle “hardware” feel).
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            t.accent.copy(alpha = 0.45f),
                            Color.Transparent
                        )
                    ),
                    topLeft = Offset(0f, size.height - 1.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())
                )
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back Button
        Box(
            modifier = Modifier
                .size(48.dp) // Slightly larger
                .scale(scale)
                .clip(CircleShape)
                .background(t.panel2.copy(alpha = 0.8f))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onBack
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White, // Absolute white for visibility
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(Modifier.size(10.dp))

        Text(
            text = title,
            color = t.onBg,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp
        )

        Spacer(Modifier.weight(1f))

        // Tiny “status dot” to keep the bar feeling alive / techy without adding clutter.
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(t.accent.copy(alpha = 0.85f))
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(t.accent.copy(alpha = 0.70f), Color.Transparent),
                            radius = size.minDimension * 2.1f
                        ),
                        radius = size.minDimension * 1.2f
                    )
                }
        )
    }
}

@Composable
private fun FloatingSectionCard(
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val t = remember { SettingsTokens() }
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(t.panel.copy(alpha = 0.92f))
            .drawBehind {
                // Soft border + corner glow. Avoid hard lines; feels “floating”.
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.30f),
                            t.strokeSoft,
                            Color.Transparent
                        )
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx(), 22.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                )
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                color = t.onBg,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            )
            Text(
                text = subtitle,
                color = t.onBgMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        content()
    }
}

@Composable
private fun InlineEditableField(
    label: String,
    value: String,
    placeholder: String,
    accent: Color,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val t = remember { SettingsTokens() }
    var editing by rememberSaveable(label) { mutableStateOf(false) }
    var text by rememberSaveable(label, value) { mutableStateOf(value) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(),
                color = t.onBgMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.3.sp
            )

            Spacer(Modifier.weight(1f))

            Icon(
                imageVector = if (editing) Icons.Rounded.Check else Icons.Rounded.Edit,
                contentDescription = if (editing) "Done" else "Edit",
                tint = (if (editing) accent else t.onBg).copy(alpha = 0.85f),
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        if (editing) {
                            editing = false
                            onCommit(text)
                        } else {
                            editing = true
                        }
                    }
                    .padding(2.dp)
            )
        }

        AnimatedContent(
            targetState = editing,
            transitionSpec = {
                (fadeIn() togetherWith fadeOut()).using(
                    SizeTransform(clip = false)
                )
            },
            label = "inline_edit_transition"
        ) { isEditing ->
            if (isEditing) {
                TechTextField(
                    value = text,
                    placeholder = placeholder,
                    accent = accent,
                    onValueChange = { text = it },
                    onDone = {
                        editing = false
                        onCommit(text)
                    }
                )
            } else {
                val display = text.ifBlank { placeholder }
                Text(
                    text = display,
                    color = if (text.isBlank()) t.onBgMuted else t.onBg.copy(alpha = 0.92f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TechTextField(
    value: String,
    placeholder: String,
    accent: Color,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = remember { SettingsTokens() }
    val shape = RoundedCornerShape(16.dp)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = TextStyle(
            color = t.onBg,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.2).sp
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.panel2.copy(alpha = 0.92f))
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = t.strokeSoft,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                )
                // Focusless glow hint: suggests “editable” without screaming.
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, accent.copy(alpha = 0.28f), Color.Transparent)
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                )
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (value.isBlank()) {
                Text(
                    text = placeholder,
                    color = t.onBgMuted.copy(alpha = 0.9f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.alpha(0.85f)
                )
            }
            inner()
        }
    )
}

@Composable
private fun VoicePersonaCard(
    persona: VoicePersona,
    selected: Boolean,
    accent: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = remember { SettingsTokens() }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.88f),
        label = "persona_press_scale"
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.9f),
        label = "persona_glow_alpha"
    )

    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(t.panel2.copy(alpha = 0.85f))
            .drawBehind {
                // Selected state: neon edge + soft bloom behind it.
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = t.strokeSoft,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx(), 18.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                )
                if (glowAlpha > 0f) {
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.22f * glowAlpha),
                                Color.Transparent
                            )
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx(), 18.dp.toPx())
                    )
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                accent.copy(alpha = 0.60f * glowAlpha),
                                Color.Transparent
                            )
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx(), 18.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                    )
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null, // soft scale + glow are the feedback
                onClick = onSelect
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Minimal icon “duotone” feel: circle chip + accent dot.
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(t.panel.copy(alpha = 0.9f))
                .drawBehind {
                    drawCircle(
                        color = accent.copy(alpha = if (selected) 0.90f else 0.28f),
                        radius = size.minDimension * 0.18f
                    )
                    if (selected) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(accent.copy(alpha = 0.55f), Color.Transparent),
                                radius = size.minDimension * 1.8f
                            ),
                            radius = size.minDimension * 0.9f
                        )
                    }
                }
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = persona.label,
                color = t.onBg,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.1).sp
            )
            Text(
                text = persona.vibe,
                color = t.onBgMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun TechCapsule(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val t = remember { SettingsTokens() }
    val shape = RoundedCornerShape(999.dp)

    Text(
        text = text,
        color = t.onBg,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
        modifier = modifier
            .clip(shape)
            .background(t.panel2.copy(alpha = 0.92f))
            .drawBehind {
                val glowPx = 18.dp.toPx()
                // Capsule highlight: small, confident “tech” edge.
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.35f),
                            t.strokeSoft,
                            Color.Transparent
                        )
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                )
                // Outer glow (extremely subtle).
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.12f), Color.Transparent),
                        radius = glowPx
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f)
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun PrimaryGlowButton(
    text: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f),
        label = "primary_btn_scale"
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 0.85f,
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = 0.9f),
        label = "primary_btn_glow"
    )

    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .height(height)
            .scale(scale)
            .clip(shape)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.95f),
                        Color(0xFF2A79FF).copy(alpha = 0.80f)
                    )
                )
            )
            .drawBehind {
                // Outer bloom behind the button (kept subtle for “premium”).
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.18f * glow), Color.Transparent),
                        radius = size.width * 0.9f
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f)
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color(0xFF061019),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * “Glass” effect helper.
 * - On Android 12L/13+ we apply a real-time blur render effect.
 * - On older devices we degrade gracefully to translucency only.
 */
private fun glassModifier(
    blurRadiusDp: Dp = 18.dp
): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Modifier
    return Modifier.graphicsLayer {
        // Do dp -> px conversion inside the graphics layer scope so the effect scales with density.
        val radiusPx = blurRadiusDp.toPx()
        renderEffect = blurEffectPx(radiusPx).asComposeRenderEffect()
        clip = true
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun blurEffectPx(radiusPx: Float): android.graphics.RenderEffect {
    return android.graphics.RenderEffect.createBlurEffect(
        radiusPx,
        radiusPx,
        android.graphics.Shader.TileMode.CLAMP
    )
}


