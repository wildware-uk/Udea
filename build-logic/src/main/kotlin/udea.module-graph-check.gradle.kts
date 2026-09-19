import dev.wildware.udea.build.ModuleGraphRules
import dev.wildware.udea.build.registerDependencyVerification
import dev.wildware.udea.build.registerEditorReleaseCheck

/**
 * Registers `udeaVerifyModuleGraph` and wires it into `check`.
 *
 * The rules are [ModuleGraphRules]; every id is documented in `docs/module-graph.md`, and a
 * failure names the id it broke, so a reader never has to work the invariant out from the
 * coordinate.
 */

plugins {
    base
}

registerDependencyVerification(
    taskName = "udeaVerifyModuleGraph",
    description = "Fails if this module breaks one of the UDEA-MG-00N module arrow rules.",
    configurationNames = ModuleGraphRules.CONFIGURATIONS,
    rules = ModuleGraphRules.ALL,
    reportFileName = "module-graph.txt",
)

// `UDEA-MG-012` (issue #233): the class half of `UDEA-MG-010`. A separate task, because it reads the
// classes a release classpath resolves to - so it has to wait for them to be built - where
// `udeaVerifyModuleGraph` reads the graph alone and runs before anything compiles.
registerEditorReleaseCheck()
