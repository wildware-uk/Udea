package dev.wildware.udea.codegen

/**
 * The KSP processor options the Udea Gradle plugin sets.
 *
 * Options rather than discovery, and that is the whole design: cross-module knowledge reaches
 * the processor as *data Gradle computed from resolved artifacts*, never as a classpath scan.
 * The generator this replaces did the opposite — it read a magic package
 * (`dev.wildware._serializer_`) through `Resolver.getDeclarationsFromPackage`, which is a
 * scan by another name, and it named its output after `System.currentTimeMillis()`, which is
 * why incremental processing had to be switched off for the whole repository.
 *
 * @param moduleName the module's name in `UpperCamelCase`. Present means "emit this module's
 *   index declarations"; absent means the module is being processed for its per-component
 *   replicators alone, which is what `udea-codegen`'s own harness runs do.
 * @param netModuleService the fully-qualified name of the `NetModule` service interface, when
 *   this module's runtime classpath has it. Generated code may only implement an interface
 *   that exists, so the emission is gated on the module actually depending on `udea-net`
 *   rather than on the processor assuming it does.
 * @param projectComponents **the whole project's** `@Replicated` component names, which is
 *   the id space every module assigns from (spec 5: "one generator, sorted FQNs"). It is
 *   required of any module that also sets [moduleName], because that module emits a lock, a
 *   `protoHash` and a `ServiceLoader` index — it is a participant in the project's wire
 *   contract, and a participant numbered from its own symbols collides with every other one.
 *   `null` is legal only for a module that emits no protocol identity at all, where the ids
 *   never leave the module; the processor refuses the combination rather than falling back.
 *   The build reads the list from the reviewed `net-components.lock` in the repository root.
 */
internal data class CodegenOptions(
    val moduleName: String?,
    val netModuleService: String?,
    val projectComponents: List<String>?,
) {
    companion object {
        const val MODULE_NAME: String = "udea.moduleName"
        const val NET_MODULE_SERVICE: String = "udea.netModuleService"

        /**
         * The whole-project component list, comma separated.
         *
         * **This is the option that makes a component type id mean one thing across a build.**
         * A KSP run only ever sees one Gradle module, so a processor that assigns ids from the
         * symbols in front of it hands out `0, 1, 2, …` *per module* — `udea-gas`'s first
         * component and `moba`'s first component both become `ComponentTypeId(0)`, and two
         * peers then decode each other's packets as the wrong component type with the
         * connect-time hash reporting agreement. Cross-module knowledge therefore arrives as
         * data the build computed from resolved artifacts, which is the same rule
         * [NET_MODULE_SERVICE] follows and the opposite of the classpath scan being retired.
         */
        const val PROJECT_COMPONENTS: String = "udea.projectComponents"

        /** How [PROJECT_COMPONENTS] separates names; a FQN can never contain one. */
        const val LIST_SEPARATOR: Char = ','

        /**
         * A module name has to be usable as the first half of a Kotlin object name, so it is
         * checked rather than sanitised: silently turning `my-game` into `MyGame` would make
         * the generated object's name depend on a rule nobody can see, and two modules could
         * sanitise to the same one.
         */
        val MODULE_NAME_FORMAT: Regex = Regex("[A-Z][A-Za-z0-9]*")

        fun from(options: Map<String, String>): CodegenOptions = CodegenOptions(
            moduleName = options[MODULE_NAME]?.takeIf(String::isNotBlank),
            netModuleService = options[NET_MODULE_SERVICE]?.takeIf(String::isNotBlank),
            projectComponents = options[PROJECT_COMPONENTS]
                ?.split(LIST_SEPARATOR)
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.takeIf(List<String>::isNotEmpty),
        )
    }
}
