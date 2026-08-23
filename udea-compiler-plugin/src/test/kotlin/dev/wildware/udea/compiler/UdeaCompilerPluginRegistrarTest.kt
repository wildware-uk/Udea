package dev.wildware.udea.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.extensions.ProjectExtensionDescriptor
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import kotlin.reflect.KClass
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

    /** The FIR extension points the registrar actually contributes to, by extension point. */
    private fun contributedExtensions(
        vararg options: Pair<String, String>,
    ): Map<KClass<out FirExtension>, List<Any>> {
        val registrars = register(*options).values.flatten().filterIsInstance<FirExtensionRegistrar>()
        assertEquals(1, registrars.size, "expected exactly one FIR extension registrar")
        // `extensions` carries a key for every FIR extension point, most of them empty; what
        // matters is which lists have anything in them.
        return registrars.single().configure().extensions.filterValues { it.isNotEmpty() }
    }

    @Test
    fun `with synthesis off the plugin registers zero FIR declaration generation extensions`() {
        // Issue #38's hard requirement, and the reason the checkers could ship unconditionally
        // while synthesis stayed gated (spec 3.2): a checker can only add a diagnostic, whereas
        // an unresolved synthesised declaration paints a whole project red in an IDE that has
        // not loaded the plugin. Issue #43's spike returned NO-GO, so this stays at zero.
        val contributed = contributedExtensions()

        assertEquals(
            emptyList(),
            contributed[FirDeclarationGenerationExtension::class].orEmpty(),
            "synthesis is gated (spec 3.2), so the plugin must contribute no " +
                "FirDeclarationGenerationExtension",
        )
        assertEquals(
            setOf(FirAdditionalCheckersExtension::class),
            contributed.keys,
            "the plugin contributes FIR checkers and nothing else",
        )
    }

    @Test
    fun `synthesis=true still registers zero FIR declaration generation extensions`() {
        // The test above pins the *default*, which is not what the NO-GO verdict says. Somebody
        // wiring synthesis behind `if (options.synthesis)` would leave it green while turning
        // the gated behaviour on for anyone who passes the option - and the option is public
        // CLI surface, so "nobody passes it" is not an argument. This drives the switch that
        // exists and asserts the verdict holds on the other side of it.
        val contributed = contributedExtensions(UdeaCompilerPlugin.OPTION_SYNTHESIS to "true")

        assertEquals(
            emptyList(),
            contributed[FirDeclarationGenerationExtension::class].orEmpty(),
            "issue #43's spike returned NO-GO: synthesis=true must select nothing until it is " +
                "reversed, whatever the CLI accepts",
        )
        assertEquals(
            setOf(FirAdditionalCheckersExtension::class),
            contributed.keys,
            "synthesis=true must change nothing at all about what the plugin contributes",
        )
    }

    @Test
    fun `assetIndex is accepted and, like synthesis, selects nothing`() {
        // Same shape, same trap: `UdeaPluginOptions.assetIndex` is parsed and read by nothing.
        // Reserved CLI surface is fine; reserved CLI surface that quietly starts selecting an
        // extension is not, and this is where that would show up.
        val contributed = contributedExtensions(UdeaCompilerPlugin.OPTION_ASSET_INDEX to "build/assets/index.json")

        assertEquals(
            setOf(FirAdditionalCheckersExtension::class),
            contributed.keys,
            "assetIndex is reserved (docs/compiler-plugin.md); nothing reads it yet",
        )
    }

    @Test
    fun `checkers=false still loads the plugin but contributes no checkers`() {
        val registered = register(UdeaCompilerPlugin.OPTION_CHECKERS to "false")

        // The adapter is still registered - `checkers` narrows what runs, only `enabled`
        // takes the plugin out of the compilation entirely.
        assertEquals(1, registered.size)
    }
}
