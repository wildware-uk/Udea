package dev.wildware.udea.assets

import com.badlogic.gdx.math.Vector2
import dev.wildware.udea.dsl.CreateDsl

/**
 * An animation asset defines a list of keyframes, for a given value to change
 * over time.
 * */
data class Animation<T>(
    val frames: List<Frame<T>>,
    val loop: Boolean = true,
) : Asset()

/**
 * A single frame of an animation.
 * */
@CreateDsl
data class Frame<T>(
    val time: Float,
    val data: T,
    val tween: Tween<T> = Tweens.DefaultTween as Tween<T>
)

/**
 * An instance of an animation.
 * */
data class AnimationInstance<T>(
    val animation: Animation<T>,
    val autoPlay: Boolean = false
) {
    private var currentFrameIndex = 0
    private var playing = autoPlay
    private var nextTime: Float = 0F
    private var time: Float = 0F

    val currentFrame: Frame<T>
        get() = animation.frames[currentFrameIndex]

    private val nextFrame: Frame<T>?
        get() = if (animation.loop) {
            animation.frames.getOrNull((currentFrameIndex + 1) % animation.frames.size)
        } else {
            animation.frames.getOrNull(currentFrameIndex + 1)
        }

    val value: T
        get() {
            val current = currentFrame
            val next = nextFrame ?: return current.data

            val frameTime = next.time
            val progress = if (frameTime > 0) {
                (time - (nextTime - frameTime)) / frameTime
            } else 0F

            return current.tween.tween(current.data, next.data, progress)
        }


    fun update(delta: Float) {
        time += delta

        if (time > nextTime) {
            currentFrameIndex++

            if (currentFrameIndex >= animation.frames.size) {
                tryLoop()
            }

            if (playing) {
                nextTime = time + animation.frames[currentFrameIndex].time
            }
        }
    }

    fun start() {
        playing = true
    }

    fun stop() {
        playing = false
    }

    private fun tryLoop() {
        if (animation.loop) {
            currentFrameIndex = 0
        } else {
            playing = false
        }
    }
}

/**
 * Defines a tween between two values.
 * */
interface Tween<T> {
    fun tween(a: T, b: T, delta: Float): T
}

@CreateDsl
class FloatTween(
    val type: TweenType = TweenType.Linear
): Tween<Float> {
    override fun tween(a: Float, b: Float, delta: Float): Float {
        return type.function(a, b, delta)
    }

    enum class TweenType(
        val function: (a: Float, b: Float, delta: Float) -> Float
    ) {
        Linear({ a, b, delta -> (b - a) * delta}),
        EaseIn({ a, b, delta -> (b - a) * delta * delta }),
        EaseOut({ a, b, delta -> (b - a) * (1 - (delta - 1) * (delta - 1)) }),
        EaseInOut({ a, b, delta -> if (delta < 0.5F) {
            (b - a) * 2 * delta * delta
        } else {
            (b - a) * 2 * (1 - (delta - 0.5F) * (delta - 0.5F)) + a * 0.5F
        } })
    }
}

object Tweens {
    /**
     * Default tween that returns the first value.
     * */
    object DefaultTween : Tween<Any> {
        override fun tween(a: Any, b: Any, delta: Float): Any {
            return a
        }
    }

    object Vector2Tween : Tween<Vector2> {
        override fun tween(a: Vector2, b: Vector2, delta: Float): Vector2 {
            return a.cpy().lerp(b, delta)
        }
    }

    object FloatTween : Tween<Float> {
        override fun tween(a: Float, b: Float, delta: Float): Float {
            return a + (b - a) * delta
        }
    }

    object IntTween : Tween<Int> {
        override fun tween(a: Int, b: Int, delta: Float): Int {
            return (a + (b - a) * delta).toInt()
        }
    }
}
