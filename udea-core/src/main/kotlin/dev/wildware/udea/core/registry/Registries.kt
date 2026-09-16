package dev.wildware.udea.core.registry

/**
 * One Gradle module's generated contribution to a game (issue #202, spec D7).
 *
 * `udea-codegen` emits exactly one implementation per module that runs it,
 * `object <Module>ModuleRegistry`, in `dev.wildware.udea.generated`. What the module contributes
 * is carried by the **facet** interfaces the same object also implements, one per kind, each
 * declared by the module that consumes that kind:
 *
 * - `dev.wildware.udea.core.level.LevelComponentModule` - the components a level file can hold;
 * - `dev.wildware.udea.net.NetModule` - the generated replicators;
 * - `dev.wildware.udea.agent.ToolModule` and `dev.wildware.udea.agent.StateModule` - the agent
 *   surface.
 *
 * Facets rather than one interface with every list on it, because this kernel cannot name a type
 * `udea-agent` declares: the module arrows point down. A consumer picks its facet out of a
 * [UdeaRegistry] with an `is` check, which reads a class the compiler already resolved - no
 * reflection, and nothing a Kotlin/Wasm or Kotlin/Native target lacks.
 *
 * This replaced run-time classpath service discovery. A service file that went missing was a
 * module that was silently absent at run time; a registry that goes missing is a reference that
 * does not compile.
 */
public interface ModuleRegistry {

    /** The module's `udea.moduleName`, in `UpperCamelCase` - e.g. `Moba`. */
    public val moduleName: String
}

/**
 * Every [ModuleRegistry] a launcher's program contains, handed to the game when it starts.
 *
 * Generated: `udea-codegen` writes `object <Module>UdeaRegistry` for each module that runs it,
 * naming the module registry of that module and of every module on its runtime classpath as a
 * static reference, in ascending fully-qualified-name order. The build computes the list from the
 * resolved classpath (`build-logic`'s `udeaModule`), and the processor refuses a listed module
 * whose registry does not exist - so a module a game depends on cannot be left out of it quietly.
 */
public interface UdeaRegistry {

    /** Every module registry, in ascending fully-qualified-name order. */
    public val modules: List<ModuleRegistry>
}
