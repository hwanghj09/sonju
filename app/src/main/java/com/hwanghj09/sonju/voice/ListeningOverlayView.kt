package com.hwanghj09.sonju.voice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.hwanghj09.sonju.R
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The home button grows into this surface, keeping the microphone in the app. */
class ListeningOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val content: View
    private val status: TextView
    private val waveform: VoiceWaveformView
    private val cancelButton: View
    private var reveal: Animator? = null
    private var animationGeneration = 0
    private var centerX = 0
    private var centerY = 0
    private var buttonRadius = 0f
    private var fullRadius = 0f
    private var speechStarted = false
    private var hasTranscript = false

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.sonju_green))
        isClickable = true
        inflate(context, R.layout.view_listening_overlay, this)
        content = findViewById(R.id.listeningContent)
        status = findViewById(R.id.listeningStatus)
        waveform = findViewById(R.id.listeningWaveform)
        cancelButton = findViewById(R.id.cancelListeningButton)
    }

    fun onCancel(action: () -> Unit) {
        cancelButton.setOnClickListener { action() }
    }

    fun showFrom(button: View) {
        stopAnimation()
        val generation = ++animationGeneration
        visibility = VISIBLE
        speechStarted = false
        hasTranscript = false
        status.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        status.setText(R.string.voice_preparing)
        waveform.setListening(false)
        content.alpha = 0f
        cancelButton.alpha = 0f
        doOnLayout {
            if (generation != animationGeneration) return@doOnLayout
            val origin = IntArray(2)
            val destination = IntArray(2)
            button.getLocationInWindow(origin)
            getLocationInWindow(destination)
            centerX = origin[0] - destination[0] + button.width / 2
            centerY = origin[1] - destination[1] + button.height / 2
            buttonRadius = min(button.width, button.height) / 2f
            fullRadius = hypot(max(centerX, width - centerX).toFloat(),
                max(centerY, height - centerY).toFloat())
            if (ValueAnimator.areAnimatorsEnabled()) {
                reveal = ViewAnimationUtils.createCircularReveal(
                    this, centerX, centerY, buttonRadius, fullRadius,
                ).apply {
                    duration = 520L
                    interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                    start()
                }
                content.translationY = 12f * resources.displayMetrics.density
                content.animate().alpha(1f).translationY(0f).setStartDelay(100L)
                    .setDuration(320L).start()
                cancelButton.animate().alpha(1f).setStartDelay(220L).setDuration(220L).start()
            } else {
                content.alpha = 1f
                content.translationY = 0f
                cancelButton.alpha = 1f
            }
        }
    }

    fun setListening(listening: Boolean) {
        if (!hasTranscript) {
            status.text = when {
                !listening -> context.getString(R.string.voice_processing)
                speechStarted -> ""
                else -> context.getString(R.string.voice_listening)
            }
        }
        waveform.setListening(listening)
    }

    fun onSpeechStarted() {
        speechStarted = true
        status.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        setListening(true)
    }

    fun showTranscript(text: String?) {
        val transcript = text?.trim()?.takeIf(String::isNotEmpty) ?: return
        speechStarted = true
        hasTranscript = true
        // Keep captions readable without TalkBack speaking every interim revision into the microphone.
        status.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_NONE
        status.text = transcript
    }

    fun updateSoundLevel(rmsDb: Float) = waveform.updateSoundLevel(rmsDb)

    fun hide(animated: Boolean, onHidden: () -> Unit) {
        ++animationGeneration
        stopAnimation()
        waveform.setListening(false)
        if (!animated || visibility != VISIBLE || !isAttachedToWindow ||
            fullRadius == 0f || !ValueAnimator.areAnimatorsEnabled()) {
            visibility = INVISIBLE
            onHidden()
            return
        }
        content.animate().alpha(0f).setStartDelay(0L).setDuration(120L).start()
        cancelButton.animate().alpha(0f).setStartDelay(0L).setDuration(100L).start()
        reveal = ViewAnimationUtils.createCircularReveal(
            this, centerX, centerY, fullRadius, buttonRadius,
        ).apply {
            duration = 320L
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = INVISIBLE
                    onHidden()
                }
            })
            start()
        }
    }

    private fun stopAnimation() {
        reveal?.removeAllListeners()
        reveal?.cancel()
        reveal = null
        content.animate().cancel()
        cancelButton.animate().cancel()
    }

    override fun onDetachedFromWindow() {
        ++animationGeneration
        stopAnimation()
        waveform.setListening(false)
        super.onDetachedFromWindow()
    }
}

/** Four round dots become soft bars in response to the recognizer's actual RMS level. */
class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val density = resources.displayMetrics.density
    private var listening = false
    private var targetLevel = 0f
    private var level = 0f
    private var lastSampleAt = 0L
    private var lastFrameAt = 0L

    fun setListening(value: Boolean) {
        listening = value
        targetLevel = 0f
        level = 0f
        lastFrameAt = 0L
        invalidate()
    }

    fun updateSoundLevel(rmsDb: Float) {
        if (!listening || !rmsDb.isFinite()) return
        targetLevel = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)
        lastSampleAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val motion = ValueAnimator.areAnimatorsEnabled()
        // Some recognition services stop sending RMS at silence; never freeze at a loud value.
        val target = if (listening && now - lastSampleAt < 180L) targetLevel else 0f
        val elapsed = if (lastFrameAt == 0L) 16f else (now - lastFrameAt).coerceIn(1, 64).toFloat()
        lastFrameAt = now
        val response = if (target > level) 65f else 180f
        level = if (motion) level + (target - level) * (1f - exp(-elapsed / response)) else target
        val phase = (now % 100_000L) / 1000f
        val diameter = 16f * density
        val spacing = 32f * density
        repeat(4) { index ->
            val wave = if (motion) (0.72f + 0.28f * sin(phase * 6f - index * 0.85f)) else 1f
            val breath = if (listening && motion) 0.5f * density * sin(phase * 2.4f - index * 0.4f) else 0f
            val barHeight = diameter + breath + 48f * density * level * wave
            val x = width / 2f + (index - 1.5f) * spacing
            canvas.drawRoundRect(x - diameter / 2f, (height - barHeight) / 2f,
                x + diameter / 2f, (height + barHeight) / 2f,
                diameter / 2f, diameter / 2f, paint)
        }
        if (listening && motion && isShown) postInvalidateOnAnimation()
    }
}
