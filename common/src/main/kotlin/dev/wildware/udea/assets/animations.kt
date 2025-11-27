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
) : Asset() {
    val duration: Float = frames.last().time
}

/**
 * A single frame of an animation.
 * */
@CreateDsl(onlyList = true)
data class Frame<T>(
    val time: Float,
    val data: T,
    val tween: Tween<T> = Tweens.DefaultTween as Tween<T>,

    /**
     * Optional name used for notify.
     * */
    val name: String? = null
)

/**
 * An instance of an animation.
 * */
data class AnimationInstance<T>(
    val animation: Animation<T>,
    val autoPlay: Boolean = true
) {
    private var playing = autoPlay
    private var time = 0F
    private val notifies = mutableMapOf<String, () -> Unit>()
    private val onFinish = mutableListOf<(AnimationInstance<T>) -> Unit>()

    private lateinit var lastFrame: Frame<T>

    var currentFrame: Frame<T> = calculateCurrentFrame()
        private set

    val isFinished: Boolean
        get() = currentFrame == animation.frames.last() && !animation.loop

    fun update(delta: Float) {
        time += delta
        lastFrame = currentFrame
        currentFrame = calculateCurrentFrame()

        if (lastFrame != currentFrame) {
            notifies[currentFrame.name]?.invoke()

            if(isFinished) {
               finish()
            }
        }
    }

    fun finish() {
        onFinish.forEach { it(this) }
    }

    fun onNotify(name: String, onNotify: () -> Unit) {
        notifies[name] = onNotify
    }

    fun onFinish(onFinish: (AnimationInstance<T>) -> Unit) {
        this.onFinish.add(onFinish)
    }

    private fun calculateCurrentFrame(): Frame<T> {
        val time = if (animation.loop) time % animation.duration else time

        return animation.frames.findLast {
            it.time < time
        } ?: animation.frames.last()
    }

    fun start() {
        playing = true
    }

    fun stop() {
        playing = false
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
) : Tween<Float> {
    override fun tween(a: Float, b: Float, delta: Float): Float {
        return type.function(a, b, delta)
    }

    enum class TweenType(
        val function: (a: Float, b: Float, delta: Float) -> Float
    ) {
        Linear({ a, b, delta -> (b - a) * delta }),
        EaseIn({ a, b, delta -> (b - a) * delta * delta }),
        EaseOut({ a, b, delta -> (b - a) * (1 - (delta - 1) * (delta - 1)) }),
        EaseInOut({ a, b, delta ->
            if (delta < 0.5F) {
                (b - a) * 2 * delta * delta
            } else {
                (b - a) * 2 * (1 - (delta - 0.5F) * (delta - 0.5F)) + a * 0.5F
            }
        })
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
