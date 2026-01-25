package com.assistant.services.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.assistant.R
import com.assistant.services.assistant.AssistantState

/**
 * Futuristic animated orb overlay for the AI Assistant.
 *
 * Features:
 * - Breathing pulse animation
 * - Rotating gradient ring
 * - State-based color morphing
 * - Audio level reactivity
 * - Thinking ripple effect
 * - Error shake animation
 * - Soft glow shadow
 */
class AssistantOverlayService(private val context: Context) {

    companion object {
        private const val TAG = "AssistantOverlay"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    
    // Main thread handler for UI operations
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Orb overlay views
    private var overlayView: View? = null
    private var ring: ImageView? = null
    private var orb: View? = null
    private var glow: View? = null

    // Edge glow overlay views
    private var edgeGlowView: View? = null
    private var edgeTop: View? = null
    private var edgeBottom: View? = null
    private var edgeLeft: View? = null
    private var edgeRight: View? = null
    // Corner accents for premium look
    private var cornerTopLeft: View? = null
    private var cornerTopRight: View? = null
    private var cornerBottomLeft: View? = null
    private var cornerBottomRight: View? = null

    // Animation references for cleanup
    private var breathingAnimator: AnimatorSet? = null
    private var ringRotationAnimator: ObjectAnimator? = null
    private var thinkingRippleAnimator: ObjectAnimator? = null
    private var ringSpeedAnimator: ObjectAnimator? = null
    private var edgePulseAnimator: ObjectAnimator? = null

    private var currentState: AssistantState = AssistantState.IDLE
    private var isShowing = false
    private var isEdgeGlowShowing = false

    // State-based edge glow colors (ARGB with alpha for soft glow)
    private object EdgeColors {
        const val LISTENING = 0x8000C6FF.toInt()      // Cyan blue
        const val UNDERSTANDING = 0x809D50BB.toInt()  // Purple
        const val SPEAKING = 0x8000F260.toInt()       // Green
        const val EXECUTION = 0x80FF8C00.toInt()      // Orange
        const val ERROR = 0x80FF416C.toInt()          // Red
        const val IDLE = 0x0000C6FF                   // Transparent
    }

    /**
     * Show the overlay orb.
     */
    fun show(state: AssistantState = AssistantState.ACTIVE_LISTENING) {
        mainHandler.post {
            if (isShowing) {
                updateStateInternal(state)
                return@post
            }

            // CRITICAL: Check overlay permission (required for TYPE_APPLICATION_OVERLAY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(context)) {
                Log.e(TAG, "❌ OVERLAY PERMISSION NOT GRANTED - Cannot show orb! User must enable 'Display over other apps' in Settings.")
                return@post
            }

            try {
                Log.d(TAG, "Creating overlay view...")
                val inflater = LayoutInflater.from(context)
                overlayView = inflater.inflate(R.layout.assistant_overlay, null)

                ring = overlayView?.findViewById(R.id.gradientRing)
                orb = overlayView?.findViewById(R.id.orbCore)
                glow = overlayView?.findViewById(R.id.glowShadow)
                
                Log.d(TAG, "Views found - ring: ${ring != null}, orb: ${orb != null}, glow: ${glow != null}")

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )

                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.y = 180

                windowManager.addView(overlayView, params)
                isShowing = true

                updateStateInternal(state)
                startBreathing()
                startRingRotation()

                Log.i(TAG, "✅ Overlay shown successfully with state: $state")
                
                // Show edge glow overlay as well
                showEdgeGlow(state)
                
                // DEBUG: Show Toast to verify overlay is working
                android.widget.Toast.makeText(context, "🔵 Orb + Edge Glow Active!", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to show overlay: ${e.message}", e)
                isShowing = false
                overlayView = null
            }
        }
    }

    /**
     * Hide the overlay orb.
     */
    fun hide() {
        mainHandler.post {
            if (!isShowing) return@post

            try {
                // Stop all animations
                breathingAnimator?.cancel()
                ringRotationAnimator?.cancel()
                thinkingRippleAnimator?.cancel()
                ringSpeedAnimator?.cancel()

                breathingAnimator = null
                ringRotationAnimator = null
                thinkingRippleAnimator = null
                ringSpeedAnimator = null

                overlayView?.let {
                    windowManager.removeView(it)
                }
                overlayView = null
                ring = null
                orb = null
                glow = null

                isShowing = false
                currentState = AssistantState.IDLE

                // Also hide edge glow
                hideEdgeGlow()

                Log.d(TAG, "Overlay hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide overlay", e)
            }
        }
    }

    /**
     * Update the orb state (changes color and animation).
     * Thread-safe wrapper that posts to main thread.
     */
    fun updateState(state: AssistantState) {
        mainHandler.post {
            updateStateInternal(state)
        }
    }
    
    /**
     * Internal state update - must be called on main thread.
     */
    private fun updateStateInternal(state: AssistantState) {
        if (!isShowing || currentState == state) return

        currentState = state

        val coreBg = when (state) {
            AssistantState.ACTIVE_LISTENING -> R.drawable.orb_core_listening
            AssistantState.UNDERSTANDING -> R.drawable.orb_core_thinking
            AssistantState.SPEAKING -> R.drawable.orb_core_speaking
            AssistantState.EXECUTION -> R.drawable.orb_core_speaking
            AssistantState.WAITING -> R.drawable.orb_core_listening
            AssistantState.IDLE -> R.drawable.orb_core_listening
        }

        orb?.setBackgroundResource(coreBg)

        // Update glow color based on state
        val glowBg = when (state) {
            AssistantState.UNDERSTANDING -> R.drawable.orb_core_thinking
            AssistantState.SPEAKING, AssistantState.EXECUTION -> R.drawable.orb_core_speaking
            else -> R.drawable.glow_shadow
        }
        glow?.setBackgroundResource(glowBg)

        // Handle state-specific animations
        when (state) {
            AssistantState.UNDERSTANDING -> {
                startThinkingRipple()
                setRingSpeed(fast = true)
            }
            AssistantState.SPEAKING, AssistantState.EXECUTION -> {
                stopThinkingRipple()
                setRingSpeed(fast = false)
            }
            else -> {
                stopThinkingRipple()
                setRingSpeed(fast = false)
            }
        }

        // Update edge glow color to match state
        if (isEdgeGlowShowing) {
            updateEdgeGlowColor(state)
        }

        Log.d(TAG, "State updated to: $state")
    }

    /**
     * Trigger error shake animation.
     */
    fun triggerError() {
        mainHandler.post {
            orb?.setBackgroundResource(R.drawable.orb_core_error)
            glow?.setBackgroundResource(R.drawable.orb_core_error)
            shakeOrb()
        }
    }

    /**
     * React to audio level (for audio-reactive visuals).
     * @param level Normalized audio level (0.0 to 1.0)
     */
    fun onAudioLevel(level: Float) {
        if (!isShowing) return

        // Map audio level to scale (0.85 to 1.3)
        val clamped = level.coerceIn(0f, 1f)
        val scale = 0.85f + (clamped * 0.45f)  // 0.85 to 1.3

        mainHandler.post {
            orb?.scaleX = scale
            orb?.scaleY = scale
        }
    }

    // ==================== PRIVATE ANIMATION METHODS ====================

    /**
     * Start breathing pulse animation.
     */
    private fun startBreathing() {
        overlayView?.let { view ->
            val scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 0.92f, 1.05f).apply {
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                duration = 1800
            }
            val scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.92f, 1.05f).apply {
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                duration = 1800
            }

            breathingAnimator = AnimatorSet().apply {
                playTogether(scaleX, scaleY)
                start()
            }
        }
    }

    /**
     * Start rotating gradient ring animation.
     */
    private fun startRingRotation() {
        ring?.let {
            ringRotationAnimator = ObjectAnimator.ofFloat(it, View.ROTATION, 0f, 360f).apply {
                repeatCount = ValueAnimator.INFINITE
                duration = 8000
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    /**
     * Set ring rotation speed.
     */
    private fun setRingSpeed(fast: Boolean) {
        ringRotationAnimator?.duration = if (fast) 3000 else 8000
    }

    /**
     * Start thinking ripple effect (alpha pulsing).
     */
    private fun startThinkingRipple() {
        orb?.let {
            thinkingRippleAnimator?.cancel()
            thinkingRippleAnimator = ObjectAnimator.ofFloat(it, View.ALPHA, 0.6f, 1f).apply {
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                duration = 700
                start()
            }
        }
    }

    /**
     * Stop thinking ripple effect.
     */
    private fun stopThinkingRipple() {
        thinkingRippleAnimator?.cancel()
        thinkingRippleAnimator = null
        orb?.alpha = 1f
    }

    /**
     * Shake the orb (for error state).
     */
    private fun shakeOrb() {
        overlayView?.let {
            val shake = ObjectAnimator.ofFloat(it, View.TRANSLATION_X, -12f, 12f).apply {
                repeatMode = ValueAnimator.REVERSE
                repeatCount = 5
                duration = 80
            }
            shake.start()
        }
    }

    /**
     * Check if overlay is currently visible.
     */
    fun isVisible(): Boolean = isShowing

    // ==================== EDGE GLOW METHODS ====================

    /**
     * Show the edge glow overlay (ambient screen border effect).
     */
    private fun showEdgeGlow(state: AssistantState) {
        if (isEdgeGlowShowing) {
            updateEdgeGlowColor(state)
            return
        }

        try {
            Log.d(TAG, "Creating edge glow overlay...")
            val inflater = LayoutInflater.from(context)
            edgeGlowView = inflater.inflate(R.layout.edge_glow_overlay, null)

            edgeTop = edgeGlowView?.findViewById(R.id.edgeGlowTop)
            edgeBottom = edgeGlowView?.findViewById(R.id.edgeGlowBottom)
            edgeLeft = edgeGlowView?.findViewById(R.id.edgeGlowLeft)
            edgeRight = edgeGlowView?.findViewById(R.id.edgeGlowRight)
            
            // Premium corner accents
            cornerTopLeft = edgeGlowView?.findViewById(R.id.cornerTopLeft)
            cornerTopRight = edgeGlowView?.findViewById(R.id.cornerTopRight)
            cornerBottomLeft = edgeGlowView?.findViewById(R.id.cornerBottomLeft)
            cornerBottomRight = edgeGlowView?.findViewById(R.id.cornerBottomRight)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            windowManager.addView(edgeGlowView, params)
            isEdgeGlowShowing = true

            updateEdgeGlowColor(state)
            startEdgePulse()

            Log.i(TAG, "✅ Edge glow overlay shown with state: $state")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to show edge glow: ${e.message}", e)
            isEdgeGlowShowing = false
            edgeGlowView = null
        }
    }

    /**
     * Hide the edge glow overlay.
     */
    private fun hideEdgeGlow() {
        if (!isEdgeGlowShowing) return

        try {
            edgePulseAnimator?.cancel()
            edgePulseAnimator = null

            edgeGlowView?.let {
                windowManager.removeView(it)
            }
            edgeGlowView = null
            edgeTop = null
            edgeBottom = null
            edgeLeft = null
            edgeRight = null

            isEdgeGlowShowing = false
            Log.d(TAG, "Edge glow hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide edge glow", e)
        }
    }

    /**
     * Update edge glow color based on assistant state.
     */
    private fun updateEdgeGlowColor(state: AssistantState) {
        val color = when (state) {
            AssistantState.ACTIVE_LISTENING -> EdgeColors.LISTENING
            AssistantState.UNDERSTANDING -> EdgeColors.UNDERSTANDING
            AssistantState.SPEAKING -> EdgeColors.SPEAKING
            AssistantState.EXECUTION -> EdgeColors.EXECUTION
            AssistantState.WAITING -> EdgeColors.LISTENING
            AssistantState.IDLE -> EdgeColors.IDLE
        }

        // Create gradient drawables programmatically for each edge
        val topDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color, color and 0x40FFFFFF, android.graphics.Color.TRANSPARENT)
        )
        val bottomDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(color, color and 0x40FFFFFF, android.graphics.Color.TRANSPARENT)
        )
        val leftDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(color, color and 0x40FFFFFF, android.graphics.Color.TRANSPARENT)
        )
        val rightDrawable = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(color, color and 0x40FFFFFF, android.graphics.Color.TRANSPARENT)
        )

        edgeTop?.background = topDrawable
        edgeBottom?.background = bottomDrawable
        edgeLeft?.background = leftDrawable
        edgeRight?.background = rightDrawable

        Log.d(TAG, "Edge glow color updated for state: $state")
    }

    /**
     * Start edge glow pulse animation.
     */
    private fun startEdgePulse() {
        edgeGlowView?.let { view ->
            edgePulseAnimator?.cancel()
            edgePulseAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, 0.6f, 1f).apply {
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                duration = 1200
                start()
            }
        }
    }
}
