import dev.wildware.udea.build.ModuleGraphRules
import dev.wildware.udea.build.registerDependencyVerification

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
