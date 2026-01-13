package com.assistant.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assistant.viewmodel.OnboardingViewModel
import com.assistant.viewmodel.OnboardingViewModelFactory

/**
 * Main onboarding chat screen.
 * 
 * Architecture:
 * - UI observes OnboardingUiState from ViewModel
 * - User input flows: UI -> ViewModel -> OnboardingStateMachine -> ViewModel -> UI
 * - UI contains NO business logic, only presentation
 * 
 * Data Flow:
 * 1. Screen observes uiState from ViewModel
 * 2. Messages list from uiState is displayed in LazyColumn
 * 3. User types in ChatInput -> calls ViewModel.updateInputText()
 * 4. User sends -> calls ViewModel.onUserInput()
 * 5. ViewModel processes through state machine and updates state
 * 6. UI recomposes with new messages
 * 7. Auto-scroll to latest message
 * 8. When completed, ViewModel emits completedProfile in uiState
 */
@Composable
fun OnboardingScreen(
    userId: String,
    onOnboardingComplete: (com.assistant.domain.model.UserProfile) -> Unit,
    viewModel: OnboardingViewModel = viewModel(
        factory = OnboardingViewModelFactory(userId)
    )
) {
    // Observe UI state from ViewModel
    val uiState by viewModel.uiState.collectAsState()
    
    // Sound player for assistant messages
    val soundPlayer = rememberMessageSoundPlayer()
    
    // LazyListState for auto-scrolling
    val listState = rememberLazyListState()
    
    // Track previous message count to detect new assistant messages
    val previousMessageCount = remember { mutableStateOf(0) }
    
    // Auto-scroll and play sound when new message is added
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            val lastMessage = uiState.messages.last()
            
            // Play sound for new assistant messages
            if (uiState.messages.size > previousMessageCount.value && lastMessage.isFromAssistant) {
                soundPlayer.playMessageSound()
            }
            
            previousMessageCount.value = uiState.messages.size
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }
    
    // Cleanup sound player when screen is disposed
    DisposableEffect(Unit) {
        onDispose {
            soundPlayer.release()
        }
    }
    
    // Emit completed profile when onboarding completes
    LaunchedEffect(uiState.completedProfile) {
        uiState.completedProfile?.let { profile ->
            onOnboardingComplete(profile)
        }
    }
    
    // Dark-first background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1F))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Chat messages area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    ChatMessageBubble(message = message)
                }
            }
            
            // Input field at bottom
            ChatInput(
                text = uiState.inputText,
                onTextChange = { viewModel.updateInputText(it) },
                onSend = { 
                    viewModel.onUserInput(uiState.inputText)
                },
                enabled = !uiState.isCompleted
            )
        }
    }
}

