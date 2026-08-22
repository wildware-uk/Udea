package dev.wildware.udea.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration

/**
 * Turns `-P plugin:dev.wildware.udea:<option>=<value>` into [UdeaConfigurationKeys] entries.
 *
 * Found by the compiler through `META-INF/services`, so it must stay public with a no-arg
 * constructor.
 */
@OptIn(ExperimentalCompilerApi::class)
public class UdeaCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = UdeaCompilerPlugin.PLUGIN_ID

    override val pluginOptions: Collection<CliOption> = listOf(
        CliOption(
            optionName = UdeaCompilerPlugin.OPTION_ENABLED,
            valueDescription = "true|false",
            description = "Register any extension at all. False is the kill switch: the plugin loads and does nothing.",
            required = false,
        ),
        CliOption(
            optionName = UdeaCompilerPlugin.OPTION_CHECKERS,
            valueDescription = "true|false",
            description = "Register the Udea FIR checkers.",
            required = false,
        ),
        CliOption(
            optionName = UdeaCompilerPlugin.OPTION_SYNTHESIS,
            valueDescription = "true|false",
            description = "Register FIR declaration synthesis. Gated behind the IDE-behaviour spike; default false.",
            required = false,
        ),
        CliOption(
            optionName = UdeaCompilerPlugin.OPTION_ASSET_INDEX,
            valueDescription = "<path>",
            description = "Reserved: a compiled asset index to validate reference(\"...\") against. Repeatable.",
            required = false,
            allowMultipleOccurrences = true,
        ),
        CliOption(
            optionName = UdeaCompilerPlugin.OPTION_KDOC_INDEX,
            valueDescription = "<path>",
            description = "Reserved: where the KDoc harvester writes its index.",
            required = false,
        ),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            UdeaCompilerPlugin.OPTION_ENABLED ->
                configuration.put(UdeaConfigurationKeys.ENABLED, option.booleanValue(value))

            UdeaCompilerPlugin.OPTION_CHECKERS ->
                configuration.put(UdeaConfigurationKeys.CHECKERS, option.booleanValue(value))

            UdeaCompilerPlugin.OPTION_SYNTHESIS ->
                configuration.put(UdeaConfigurationKeys.SYNTHESIS, option.booleanValue(value))

            UdeaCompilerPlugin.OPTION_ASSET_INDEX ->
                configuration.appendList(UdeaConfigurationKeys.ASSET_INDEX, value)

            UdeaCompilerPlugin.OPTION_KDOC_INDEX ->
                configuration.put(UdeaConfigurationKeys.KDOC_INDEX, value)

            else -> throw CliOptionProcessingException(
                "Unknown option '${option.optionName}' for plugin '${UdeaCompilerPlugin.PLUGIN_ID}'.",
            )
        }
    }

    /**
     * Strict on purpose. A typo in a Gradle-generated argument that silently reads as
     * `false` would switch the checkers off without anyone noticing — exactly the failure
     * the kill switch is supposed to make visible.
     */
    private fun AbstractCliOption.booleanValue(value: String): Boolean =
        value.toBooleanStrictOrNull() ?: throw CliOptionProcessingException(
            "Option '$optionName' of plugin '${UdeaCompilerPlugin.PLUGIN_ID}' expects " +
                "'true' or 'false', got '$value'.",
        )
}
