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
 */
internal data class CodegenOptions(
    val moduleName: String?,
    val netModuleService: String?,
) {
    companion object {
        const val MODULE_NAME: String = "udea.moduleName"
        const val NET_MODULE_SERVICE: String = "udea.netModuleService"

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
        )
    }
}
