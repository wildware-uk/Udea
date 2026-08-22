package dev.wildware.udea.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCompilerApi::class)
class UdeaCommandLineProcessorTest {

    private val processor = UdeaCommandLineProcessor()

    private fun option(name: String): AbstractCliOption =
        processor.pluginOptions.single { it.optionName == name }

    private fun configure(vararg pairs: Pair<String, String>): UdeaPluginOptions {
        val configuration = CompilerConfiguration()
        for ((name, value) in pairs) {
            processor.processOption(option(name), value, configuration)
        }
        return configuration.toUdeaPluginOptions()
    }

    @Test
    fun `the plugin id is the one the gradle side writes`() {
        assertEquals("dev.wildware.udea", processor.pluginId)
    }

    @Test
    fun `an untouched configuration yields the documented defaults`() {
        val options = configure()

        assertEquals(true, options.enabled, "the plugin is on unless switched off")
        assertEquals(true, options.checkers, "checkers only add diagnostics, so they ship on")
        assertEquals(false, options.synthesis, "declaration synthesis is gated (spec 3.2)")
        assertEquals(emptyList(), options.assetIndex)
        assertEquals(null, options.kdocIndex)
    }

    @Test
    fun `every option round-trips through the configuration`() {
        val options = configure(
            UdeaCompilerPlugin.OPTION_ENABLED to "false",
            UdeaCompilerPlugin.OPTION_CHECKERS to "false",
            UdeaCompilerPlugin.OPTION_SYNTHESIS to "true",
            UdeaCompilerPlugin.OPTION_ASSET_INDEX to "build/assets/one.index",
            UdeaCompilerPlugin.OPTION_ASSET_INDEX to "build/assets/two.index",
            UdeaCompilerPlugin.OPTION_KDOC_INDEX to "build/kdoc.index",
        )

        assertEquals(
            UdeaPluginOptions(
                enabled = false,
                checkers = false,
                synthesis = true,
                assetIndex = listOf("build/assets/one.index", "build/assets/two.index"),
                kdocIndex = "build/kdoc.index",
            ),
            options,
        )
    }

    @Test
    fun `only assetIndex may be repeated`() {
        val repeatable = processor.pluginOptions.filter { it.allowMultipleOccurrences }
        assertEquals(
            listOf(UdeaCompilerPlugin.OPTION_ASSET_INDEX),
            repeatable.map { it.optionName },
        )
    }

    @Test
    fun `no option is required, so a bare -Xplugin is a valid configuration`() {
        assertTrue(processor.pluginOptions.none { it.required })
    }

    @Test
    fun `a boolean option rejects anything that is not true or false`() {
        // A silently-false "enabled" would switch the checkers off without a trace, which
        // is the one failure the kill switch exists to make visible.
        for (bad in listOf("", "yes", "TRUE", "0", "1", "off")) {
            val failure = assertFailsWith<CliOptionProcessingException>("value '$bad' should be rejected") {
                configure(UdeaCompilerPlugin.OPTION_ENABLED to bad)
            }
            assertTrue(
                failure.message.orEmpty().contains("'$bad'"),
                "the message should quote the offending value, was: ${failure.message}",
            )
        }
    }

    @Test
    fun `an unknown option name is rejected rather than ignored`() {
        val unknown: AbstractCliOption = CliOption("notAnOption", "<v>", "made up", required = false)
        val failure = assertFailsWith<CliOptionProcessingException> {
            processor.processOption(unknown, "x", CompilerConfiguration())
        }
        assertTrue(failure.message.orEmpty().contains("notAnOption"), failure.message)
    }
}
