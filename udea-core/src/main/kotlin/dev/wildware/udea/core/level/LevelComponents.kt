package dev.wildware.udea.core.level

import com.github.quillraven.fleks.Component
import java.util.ServiceLoader
import kotlin.reflect.KClass
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.PolymorphicModuleBuilder

/**
 * One component type a level file can hold: its class and the serializer the kotlinx plugin
 * generated for it.
 *
 * A level stores Fleks' `world.snapshot()`, whose component list is polymorphic over
 * [Component]. kotlinx will only encode a polymorphic value whose concrete class has been
 * registered, and it resolves them by name rather than by scanning the classpath - which is the
 * point: no reflection decides what a level may contain.
 *
 * Nobody writes one of these by hand. `udea-codegen` emits one per `@Serializable` component in a
 * module, inside that module's generated [LevelComponentModule], because a hand-kept list goes
 * stale the first time somebody adds a component - and the failure then is a level that refuses
 * to save, found by an editor user rather than by the build.
 */
public class LevelComponent<T : Component<T>>(
    /** The concrete component class. */
    public val type: KClass<T>,
    /** Its plugin-generated serializer. */
    public val serializer: KSerializer<T>,
) {
    /** The name the class is written under in a level file, and read back by. */
    public val serialName: String get() = serializer.descriptor.serialName

    internal fun registerInto(builder: PolymorphicModuleBuilder<Component<*>>) {
        builder.subclass(type, serializer)
    }

    override fun toString(): String = "LevelComponent($serialName)"
}

/**
 * Every saveable component one Gradle module declares, found through `ServiceLoader`.
 *
 * Generated: `udea-codegen` writes `<Module>LevelComponents` and its
 * `META-INF/services` entry for every module that runs KSP with `udea.moduleName` set and
 * declares at least one `@Serializable` Fleks component. A class rather than a Kotlin `object`
 * for the reason every other generated index is one - `ServiceLoader` needs a public no-arg
 * constructor.
 */
public interface LevelComponentModule {

    /** The Gradle module the list was generated for. */
    public val moduleName: String

    /** The module's saveable components, in ascending qualified-name order. */
    public val components: List<LevelComponent<*>>

    public companion object {

        /**
         * Every [LevelComponentModule] on [loader]'s classpath, in ascending module-name order so
         * the result does not depend on classpath order.
         */
        public fun discover(
            loader: ClassLoader = LevelComponentModule::class.java.classLoader,
        ): List<LevelComponentModule> =
            ServiceLoader.load(LevelComponentModule::class.java, loader)
                .sortedBy { it.moduleName }
    }
}
