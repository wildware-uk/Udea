package dev.wildware.udea.render.support

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.GdxNativesLoader
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * A `Gdx.gl` and a `Gdx.graphics` that answer questions without a driver behind them.
 *
 * ## Why this exists rather than "those tests need a window"
 *
 * A `Viewport.apply()` calls `HdpiUtils.glViewport`, which reads `Gdx.gl` and `Gdx.graphics`.
 * Nothing about *what rectangle a viewport computes* needs a GPU — it is arithmetic over two
 * sizes and an aspect ratio — but one static field lookup is enough to make the whole camera
 * and UI layer untestable without a display. That is precisely how the old tree ended up with
 * no test at all for `GameScreen.resize`.
 *
 * A `java.lang.reflect.Proxy` rather than a hand-written class because `GL20` has some two
 * hundred methods and `Graphics` around fifty: written out, the double would be four thousand
 * lines of noise in which the four methods that matter would be invisible.
 *
 * It is a **test** double and deliberately lives in test sources. Nothing shipped may reach it.
 */
internal class HeadlessGl private constructor(
    private val width: Int,
    private val height: Int,
) {

    /** Every `glViewport` call, as `x,y,width,height`, so a test can assert the rectangle. */
    val viewports = ArrayList<String>()

    private val glHandler = InvocationHandler { _, method: Method, args: Array<Any?>? ->
        if (method.name == "glViewport" && args != null) {
            viewports += args.joinToString(",")
        }
        defaultFor(method)
    }

    /**
     * `Gdx.app`, answering only `getType()`.
     *
     * `Stage.act` branches on the application type to decide whether to fire mouse enter/exit
     * events, and reads the static unguarded. `HeadlessDesktop` is the honest answer here and
     * takes the branch that needs no input device.
     */
    private val appHandler = InvocationHandler { _, method: Method, _ ->
        when (method.name) {
            "getType" -> Application.ApplicationType.HeadlessDesktop
            else -> defaultFor(method)
        }
    }

    private val graphicsHandler = InvocationHandler { _, method: Method, _ ->
        when (method.name) {
            "getWidth", "getBackBufferWidth" -> width
            "getHeight", "getBackBufferHeight" -> height
            "getDeltaTime", "getRawDeltaTime" -> 0f
            else -> defaultFor(method)
        }
    }

    fun install() {
        // gdx-math is not pure Kotlin: `Matrix4.prj`, `inv` and `mul` are native, so a camera
        // update fails with an UnsatisfiedLinkError until the desktop natives are extracted.
        // A real backend does this while creating its window; a test with no window has to ask.
        // The call is idempotent and cheap after the first.
        GdxNativesLoader.load()
        Gdx.gl = proxy(GL20::class.java, glHandler)
        Gdx.gl20 = Gdx.gl
        Gdx.graphics = proxy(Graphics::class.java, graphicsHandler)
        Gdx.app = proxy(Application::class.java, appHandler)
    }

    fun uninstall() {
        Gdx.gl = null
        Gdx.gl20 = null
        Gdx.graphics = null
        Gdx.app = null
    }

    companion object {

        /**
         * Installs a fake GL for the duration of [block] and removes it afterwards.
         *
         * `Gdx.gl` is a global, and a test that left one installed would change the behaviour
         * of every test that ran after it in the same JVM — including the ones that check what
         * happens when there is *no* context. The `finally` is the whole point.
         */
        fun <T> using(width: Int = 1280, height: Int = 720, block: (HeadlessGl) -> T): T {
            val gl = installed(width, height)
            try {
                return block(gl)
            } finally {
                gl.uninstall()
            }
        }

        /**
         * Installs a fake GL and hands it back, for a `@BeforeEach`/`@AfterEach` pair.
         *
         * The caller **must** call [uninstall]: `Gdx.gl` is a global, and a leaked one changes
         * the behaviour of every test that runs after it in the same JVM -- including the ones
         * that check what happens when there is no context at all.
         */
        fun installed(width: Int = 1280, height: Int = 720): HeadlessGl =
            HeadlessGl(width, height).also { it.install() }

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T

        /** A zero of the method's return type, or `null` for a reference type. */
        private fun defaultFor(method: Method): Any? = when (method.returnType) {
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Boolean::class.javaPrimitiveType -> false
            Void.TYPE -> null
            else -> null
        }
    }
}
