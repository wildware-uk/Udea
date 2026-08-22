package dev.wildware.udea.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.extensions.ProjectExtensionDescriptor
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The inner half of the kill switch: what the registrar does with `enabled=false`.
 *
 * `ExtensionStorage` is the compiler's own collector, so "registered zero extensions" is
 * asserted against the real structure the compiler goes on to read, not a stand-in.
 */
@OptIn(ExperimentalCompilerApi::class)
class UdeaCompilerPluginRegistrarTest {

    private fun register(
        vararg options: Pair<String, String>,
    ): Map<ProjectExtensionDescriptor<*>, List<Any>> {
        val configuration = CompilerConfiguration()
        val processor = UdeaCommandLineProcessor()
        for ((name, value) in options) {
            processor.processOption(
                processor.pluginOptions.single { it.optionName == name } as AbstractCliOption,
                value,
                configuration,
            )
        }
        val storage = CompilerPluginRegistrar.ExtensionStorage()
        with(UdeaCompilerPluginRegistrar()) { storage.registerExtensions(configuration) }
        return storage.registeredExtensions
    }

    @Test
    fun `the registrar declares K2 support`() {
        assertTrue(UdeaCompilerPluginRegistrar().supportsK2)
    }

    @Test
    fun `enabled=false registers zero extensions`() {
        val registered = register(UdeaCompilerPlugin.OPTION_ENABLED to "false")

        assertTrue(
            registered.isEmpty(),
            "with the kill switch thrown the plugin must load inertly (spec 7), " +
                "but it registered $registered",
        )
    }

    @Test
    fun `the default configuration registers exactly one FIR extension registrar`() {
        val registered = register()

        assertEquals(
            listOf<ProjectExtensionDescriptor<*>>(FirExtensionRegistrarAdapter.Companion),
            registered.keys.toList(),
            "the plugin contributes FIR extensions and nothing else - no IR, ever (spec 3.2)",
        )
        val extensions = registered.values.single()
        assertEquals(1, extensions.size)
        assertTrue(extensions.single() is FirExtensionRegistrarAdapter)
    }

    @Test
    fun `checkers=false still loads the plugin but contributes no checkers`() {
        val registered = register(UdeaCompilerPlugin.OPTION_CHECKERS to "false")

        // The adapter is still registered - `checkers` narrows what runs, only `enabled`
        // takes the plugin out of the compilation entirely.
        assertEquals(1, registered.size)
    }
}
