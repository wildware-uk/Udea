import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget

@KotlinScript(
    fileExtension = "udea.kts",
    compilationConfiguration = UdeaScriptConfiguration::class,
)
abstract class UdeaScript {
    fun weCantCallThis() {
        println("We cant call this!")
    }
}

object UdeaScriptConfiguration : ScriptCompilationConfiguration({
    // Common defaults for engine scripts
    defaultImports(
        "kotlin.math.*",
        "com.badlogic.gdx.math.*",
        "dev.wildware.udea.ecs.component.*",
        "dev.wildware.udea.dsl.*",
        "dev.wildware.udea.dsl.blueprint",
        "dev.wildware.udea.assets.*"
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
