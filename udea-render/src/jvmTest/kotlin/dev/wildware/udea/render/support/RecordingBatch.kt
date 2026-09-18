package dev.wildware.udea.render.support

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * A [Batch] that records what it was asked to draw instead of drawing it.
 *
 * This is why `RenderTargets.batch` is typed as the `Batch` **interface** and not as
 * `SpriteBatch`: every ported drawing system can then be driven, and its output asserted, in a
 * plain JVM with no window. The old tree's drawing systems each constructed their own
 * `SpriteBatch` in a field initialiser, so none of them could be instantiated in a test at all,
 * and consequently none of them had one.
 *
 * A `java.lang.reflect.Proxy` for the same reason as [HeadlessGl]: `Batch` has around forty
 * methods, thirty-odd of which are `draw` overloads nobody here calls, and writing them out
 * would bury the handful that carry the assertions.
 */
internal class RecordingBatch {

    /** Every call, in order: `begin`, `end`, and one `draw(...)` line per drawn thing. */
    val calls = ArrayList<String>()

    /** Draw calls made while the batch was begun. The number a port test counts. */
    val draws: List<Draw> get() = drawn

    private val drawn = ArrayList<Draw>()

    private var drawing = false

    private val color = Color(Color.WHITE)

    private val projection = Matrix4()

    private val transform = Matrix4()

    /** True if `end()` was ever called without a matching `begin()`, or vice versa. */
    var mismatchedBeginEnd: Boolean = false
        private set

    val batch: Batch = Proxy.newProxyInstance(
        Batch::class.java.classLoader,
        arrayOf(Batch::class.java),
        InvocationHandler { _, method, args -> handle(method, args) },
    ) as Batch

    private fun handle(method: Method, args: Array<Any?>?): Any? = when (method.name) {
        "begin" -> {
            if (drawing) mismatchedBeginEnd = true
            drawing = true
            calls += "begin"
            null
        }

        "end" -> {
            if (!drawing) mismatchedBeginEnd = true
            drawing = false
            calls += "end"
            null
        }

        "isDrawing" -> drawing
        "getColor" -> color
        "setColor" -> {
            (args?.firstOrNull() as? Color)?.let(color::set)
            null
        }

        "getProjectionMatrix" -> projection
        "setProjectionMatrix" -> {
            (args?.firstOrNull() as? Matrix4)?.let(projection::set)
            null
        }

        "getTransformMatrix" -> transform
        "draw" -> {
            if (!drawing) mismatchedBeginEnd = true
            drawn += describe(args)
            calls += "draw"
            null
        }

        else -> defaultFor(method)
    }

    private fun describe(args: Array<Any?>?): Draw {
        val values = args.orEmpty()
        val region = values.firstOrNull() as? TextureRegion
        val floats = values.filterIsInstance<Float>()
        return Draw(region, floats, Color(color))
    }

    /** One recorded `draw` call: what, where, and in what colour. */
    class Draw(
        val region: TextureRegion?,
        /** The float arguments in declaration order: `x`, `y`, and whatever followed. */
        val floats: List<Float>,
        val color: Color,
    ) {
        val x: Float get() = floats.getOrElse(0) { Float.NaN }
        val y: Float get() = floats.getOrElse(1) { Float.NaN }

        override fun toString(): String = "Draw($region at ($x, $y), $floats)"
    }

    private companion object {
        fun defaultFor(method: Method): Any? = when (method.returnType) {
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Boolean::class.javaPrimitiveType -> false
            else -> null
        }
    }
}
