package io.github.flipcover.controls

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.compose.animation.core.FloatSpringSpec
import kotlin.math.max
import kotlin.math.min

/** View host for Compose's spring solver; no Composition or idle frame loop. */
internal class DetailSheetMotion(private val card: View, private val veil: View) {
    // Liquid Glass DampedDragAnimation uses separate X/Y springs (0.6/0.7, 250).
    // Match the travel's first rebound to the stronger, staggered scale rebounds.
    private val openingSpecs = arrayOf(
        FloatSpringSpec(0.56f, 350f, 0.001f),
        FloatSpringSpec(0.66f, 350f, 0.001f),
        FloatSpringSpec(0.72f, 350f, 0.5f),
        FloatSpringSpec(0.72f, 350f, 0.5f),
        FloatSpringSpec(1f, 1000f, 0.001f)
    )
    private val closingSpecs = openingSpecs.copyOf().apply {
        this[2] = FloatSpringSpec(1f, 1000f, 0.5f)
        this[3] = FloatSpringSpec(1f, 1000f, 0.5f)
    }
    private val velocity = FloatArray(5)
    private var animator: ValueAnimator? = null

    fun animateTo(scale: Float, x: Float, y: Float, visible: Boolean, finished: Runnable?) {
        // Re-target from the current pose AND velocity, including an interrupted opening.
        stopClock()
        val specs = if (visible) openingSpecs else closingSpecs
        val start = floatArrayOf(card.scaleX, card.scaleY, card.translationX, card.translationY, veil.alpha)
        val initialVelocity = velocity.copyOf()
        // Alpha must not overshoot or briefly brighten when reversing the spring.
        initialVelocity[4] = 0f
        val target = floatArrayOf(scale, scale, x, y, if (visible) 1f else 0f)
        val durations = LongArray(5) { specs[it].getDurationNanos(start[it], target[it], initialVelocity[it]) }
        // Closing is complete when the veil is invisible. Do not leave an invisible
        // modal mounted while the longer scale springs finish their numeric tail.
        val length = max(1L, if (visible) durations.maxOrNull() ?: 1L else durations[4])
        val values = FloatArray(5)
        val clock = ValueAnimator.ofFloat(0f, 1f)
        animator = clock
        clock.duration = max(1L, (length + 999_999L) / 1_000_000L)
        clock.interpolator = LinearInterpolator() // Only the clock is linear; values come from the spring solver.
        clock.addUpdateListener {
            val time = ((it.animatedValue as Float).toDouble() * length).toLong()
            for (index in values.indices) {
                if (time >= durations[index]) { values[index] = target[index]; velocity[index] = 0f }
                else {
                    values[index] = specs[index].getValueFromNanos(time, start[index], target[index], initialVelocity[index])
                    velocity[index] = specs[index].getVelocityFromNanos(time, start[index], target[index], initialVelocity[index])
                }
            }
            apply(values)
        }
        clock.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator !== clock) return
                animator = null
                velocity.fill(0f)
                apply(target)
                finished?.run()
            }
        })
        clock.start()
    }

    private fun apply(values: FloatArray) {
        // Constrain travel and extra scale together so a positional rebound cannot
        // push even the normal-sized card through the existing safe-area boundary.
        val halfX = card.width * values[0].coerceIn(0.001f, 1f) / 2f
        val halfY = card.height * values[1].coerceIn(0.001f, 1f) / 2f
        val rawX = card.left + card.width / 2f + values[2]
        val rawY = card.top + card.height / 2f + values[3]
        val centerX = rawX.coerceIn(halfX, max(halfX, veil.width - halfX))
        val centerY = rawY.coerceIn(halfY, max(halfY, veil.height - halfY))
        val limitX = max(1f, 2f * min(centerX, veil.width - centerX) / max(1, card.width))
        val limitY = max(1f, 2f * min(centerY, veil.height - centerY) / max(1, card.height))
        card.scaleX = values[0].coerceIn(0.001f, limitX)
        card.scaleY = values[1].coerceIn(0.001f, limitY)
        card.translationX = if (centerX == rawX) values[2] else centerX - card.left - card.width / 2f
        card.translationY = if (centerY == rawY) values[3] else centerY - card.top - card.height / 2f
        // A constrained axis has stopped at the boundary. Never reintroduce its
        // hidden, outward solver velocity when the user reverses the animation.
        if (card.scaleX != values[0]) velocity[0] = 0f
        if (card.scaleY != values[1]) velocity[1] = 0f
        if (centerX != rawX) velocity[2] = 0f
        if (centerY != rawY) velocity[3] = 0f
        veil.alpha = values[4].coerceIn(0f, 1f)
    }

    private fun stopClock() {
        val previous = animator
        animator = null
        previous?.removeAllListeners()
        previous?.removeAllUpdateListeners()
        previous?.cancel()
    }

    fun cancel() { stopClock(); velocity.fill(0f) }
}
