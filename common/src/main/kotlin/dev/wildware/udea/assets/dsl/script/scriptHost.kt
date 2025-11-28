package dev.wildware.udea.assets.dsl.script

import dev.wildware.udea.UdeaGameManager
import java.io.File
import java.lang.System.currentTimeMillis
import java.security.MessageDigest
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.*
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<UdeaScript> {
    jvm {
        // Make the full classpath visible to scripts so they can access engine and game types
        dependenciesFromClassContext(UdeaGameManager::class, wholeClasspath = true)
        jvmTarget("17")

        hostConfiguration(ScriptingHostConfiguration {
            jvm {
                compilationCache(
                    CompiledScriptJarsCache { script, configuration ->
                        File("./scripts/cache", compiledScriptUniqueName(script, configuration) + ".jar").apply {
                            parentFile.mkdirs()
                        }
                    }
                )
            }
        })
    }
}

val evaluationConfiguration = ScriptEvaluationConfiguration {
    jvm {
        baseClassLoader(UdeaGameManager::class.java.classLoader)
    }
}

private val scriptingHost = BasicJvmScriptingHost()

/**
 * Simple script harness for evaluating .udea.kts scripts.
 * - Uses full application classpath for dependencies so scripts can see engine/game classes
 * - Adds convenient default imports used by blueprint scripts
 */
suspend fun evalScript(
    scriptFile: File,
): ResultWithDiagnostics<EvaluationResult> {
    val preCompileTime = currentTimeMillis()
    val scriptSource = scriptFile.toScriptSource()
    val compiledScript = try {
        scriptingHost.compiler(scriptSource, compilationConfiguration)
            .valueOrThrow()
    } catch (e: Exception) {
        error("Failed to compile ${scriptFile.name} ${e.message}\n\n${e.stackTraceToString()}")
    }
    println("Compiled ${scriptFile.name} in ${currentTimeMillis() - preCompileTime}ms")

    val preRunTime = currentTimeMillis()
    val value = scriptingHost.evaluator(compiledScript, evaluationConfiguration)
    println("Evaluated ${scriptFile.name} in ${currentTimeMillis() - preRunTime}ms")

    return value
}

@OptIn(ExperimentalStdlibApi::class)
private fun compiledScriptUniqueName(
    script: SourceCode,
    scriptCompilationConfiguration: ScriptCompilationConfiguration
): String {
    val digestWrapper = MessageDigest.getInstance("MD5")
    digestWrapper.update(script.text.toByteArray())
    scriptCompilationConfiguration.notTransientData.entries
        .sortedBy { it.key.name }
        .forEach {
            digestWrapper.update(it.key.name.toByteArray())
            digestWrapper.update(it.value.toString().toByteArray())
        }
    return digestWrapper.digest().toHexString()
}
