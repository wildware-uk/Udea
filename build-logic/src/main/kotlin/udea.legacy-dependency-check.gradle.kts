import dev.wildware.udea.build.LegacyDependencyRules
import dev.wildware.udea.build.registerDependencyVerification

/**
 * Registers `udeaVerifyNoLegacyDependencies` and wires it into `check`.
 *
 * Applied by the root build to every project whose path starts with `:udea-` or is `:moba`.
 * The rule is [LegacyDependencyRules]; this file exists only to attach it to a project.
 *
 * Note what it does **not** apply: no Kotlin, no toolchain, no version catalog. A gate a
 * project can only get by also taking a convention is a gate that stops applying the day
 * someone writes a module that needs a different convention.
 */

plugins {
    base
}

registerDependencyVerification(
    taskName = "udeaVerifyNoLegacyDependencies",
    description = "Fails if an old-tree project resolves onto any classpath of this module.",
    configurationNames = LegacyDependencyRules.CONFIGURATIONS,
    rules = listOf(LegacyDependencyRules.RULE),
    reportFileName = "legacy-dependencies.txt",
)
