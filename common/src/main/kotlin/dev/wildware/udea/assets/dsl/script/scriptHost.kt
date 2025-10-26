package dev.wildware.udea.assets.dsl.script

import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Simple script harness for evaluating .udea.kts scripts.
 * - Uses full application classpath for dependencies so scripts can see engine/game classes
 * - Adds convenient default imports used by blueprint scripts
 */
fun evalScript(scriptFile: File): ResultWithDiagnostics<EvaluationResult> {
    val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<UdeaScript> {
        jvm {
            // Make the full classpath visible to scripts so they can access engine and game types
            dependenciesFromCurrentContext(wholeClasspath = true)
            jvmTarget("17")
        }
    }

    return BasicJvmScriptingHost().eval(scriptFile.toScriptSource(), compilationConfiguration, null)
}
