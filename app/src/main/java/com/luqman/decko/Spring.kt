package com.luqman.decko

import android.animation.ValueAnimator
import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A damped spring stepped on the display's frame clock. Unlike a fixed-duration animator it starts from
 * the gesture's velocity, so a fling flows into the settle; [dampingRatio] below 1 overshoots slightly.
 */
class Spring(
    private val stiffness: Float,
    private val dampingRatio: Float,
    private val onUpdate: (Float) -> Unit,
    private val onEnd: () -> Unit = {},
) : Choreographer.FrameCallback {
    var value = 0f
        private set
    var isRunning = false
        private set

    private var velocity = 0f
    private var target = 0f
    private var lastFrame = 0L

    /** [velocity] is in value units per second. */
    fun animateTo(target: Float, from: Float = value, velocity: Float = 0f) {
        this.target = target
        value = from
        this.velocity = velocity
        if (!ValueAnimator.areAnimatorsEnabled()) {
            snap()
            return
        }
        if (!isRunning) {
            isRunning = true
            lastFrame = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun cancel() {
        if (!isRunning) return
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        // First frame has no previous one; assume one 60 Hz frame. Long stalls are capped so it can't explode.
        val dt = if (lastFrame == 0L) 1 / 60f else ((frameTimeNanos - lastFrame) / 1e9f).coerceAtMost(1 / 20f)
        lastFrame = frameTimeNanos
        val omega = sqrt(stiffness)
        val damping = 2 * dampingRatio * omega
        // Semi-implicit Euler in small steps stays stable at these stiffnesses.
        val steps = 4
        val h = dt / steps
        repeat(steps) {
            val accel = -stiffness * (value - target) - damping * velocity
            velocity += accel * h
            value += velocity * h
        }
        if (abs(value - target) < REST_DISTANCE && abs(velocity) < REST_VELOCITY) return snap()
        onUpdate(value)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun snap() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        value = target
        velocity = 0f
        onUpdate(value)
        onEnd()
    }

    private companion object {
        const val REST_DISTANCE = 0.0015f
        const val REST_VELOCITY = 0.02f
    }
}
