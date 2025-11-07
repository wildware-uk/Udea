package dev.wildware.udea.assets.dsl.script

import dev.wildware.udea.UdeaGameManager
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget

@KotlinScript(
    fileExtension = "udea.kts",
    compilationConfiguration = UdeaScriptConfiguration::class,
    evaluationConfiguration = UdeaEvaluationConfiguration::class
)
abstract class UdeaScript

object UdeaEvaluationConfiguration : ScriptEvaluationConfiguration({
    jvm {
        baseClassLoader(UdeaGameManager::class.java.classLoader)
    }
}) {
    private fun readResolve(): Any = UdeaEvaluationConfiguration
}

object UdeaScriptConfiguration : ScriptCompilationConfiguration({
    // Common defaults for engine scripts
    defaultImports(
        "kotlin.math.*",
        "com.badlogic.gdx.math.*",
        "dev.wildware.udea.ecs.component.*",
        "dev.wildware.udea.dsl.*",
        "dev.wildware.udea.dsl.blueprint"
    )

    ide {
        acceptedLocations(ScriptAcceptedLocation.Everywhere)
    }

    jvm {
        jvmTarget("17")
    }

    compilerOptions.append("-Xcontext-sensitive-resolution")
    baseClass(UdeaScript::class)
}) {
    private fun readResolve(): Any = UdeaScriptConfiguration
}
