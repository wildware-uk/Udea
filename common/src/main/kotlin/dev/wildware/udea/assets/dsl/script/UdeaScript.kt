package dev.wildware.udea.assets.dsl.script

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvm.util.classpathFromClass

@KotlinScript(
    fileExtension = "udea.kts",
    compilationConfiguration = UdeaScriptConfiguration::class
)
abstract class UdeaScript

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
