import dev.wildware.udea.build.ModuleGraphRules
import dev.wildware.udea.build.registerDependencyVerification

/**
 * Registers `udeaVerifyModuleGraph` and wires it into `check`.
 *
 * The rules are [ModuleGraphRules]; every id is documented in `docs/module-graph.md`. This
 * runs *beside* `udeaVerifyNoLegacyDependencies` rather than absorbing it, so a failure
 * says which invariant broke without a reader having to work it out from the coordinate.
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
