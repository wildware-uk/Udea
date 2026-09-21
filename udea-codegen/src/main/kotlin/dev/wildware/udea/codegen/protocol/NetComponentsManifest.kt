package dev.wildware.udea.codegen.protocol

/**
 * What a module tells the build about the `@Replicated` components it compiles (issue #274).
 *
 * The id space itself arrives the other way round - the build reads the reviewed
 * `net-components.lock` and hands it to the processor as `udea.projectComponents`, because an id
 * is a promise made in a diff somebody read rather than a number a processor counted out. This
 * file is the return leg, and it exists for exactly one job: `udeaWriteNetComponents` has to be
 * able to write that reviewed file the first time, when there is nothing to hand in yet.
 *
 * Two properties make it work, and both are deliberate.
 *
 * **It is written before the id space is checked.** The KSP run that fails for want of a registry
 * still leaves this behind, so the task that fixes the failure can read it. A manifest written
 * after the check would only ever exist on builds that did not need it.
 *
 * **It carries names and no ids.** Nothing here is protocol identity: it is the question, not the
 * answer. `<Module>-net-protocol.lock` is the answer, and it is still emitted only when the
 * module has an id space to number from.
 */
internal object NetComponentsManifest {

    /**
     * The manifest's name. **Mirrored by `UdeaNetComponents.MANIFEST_NAME` in `build-logic`**,
     * which globs for it under each project's build directory; `UdeaNetComponentsTest` reads this
     * file and fails when the two stop agreeing.
     */
    const val FILE_NAME: String = "net-components.txt"

    /**
     * `udea/Moba-net-components.txt` - where it lands in the module's generated resources.
     *
     * Module-qualified for the reason [ProtocolLock.resourcePath] is: two modules contributing
     * one classpath resource path means whichever jar comes first wins, silently.
     */
    fun resourcePath(moduleName: String): String = "udea/$moduleName-$FILE_NAME"

    /**
     * The file's whole content: one fully-qualified name per line, **ascending**.
     *
     * The order is the contract, not the tidiness: `udeaWriteNetComponents` folds these names
     * into `net-components.lock`, where a name's position is its `ComponentTypeId` on the wire.
     * A file emitted in visit order would differ between two machines compiling identical
     * sources.
     *
     * `UdeaSymbolProcessor` already hands its components over sorted, so this sort is the second
     * one on the same path - and it is kept, because it is the one a caller can rely on and the
     * one `GeneratedFileDeterminismTest.the component manifest emitter sorts what it is handed`
     * can actually observe. An end-to-end assertion cannot: with this sort deleted the upstream
     * one still produces a sorted file.
     */
    fun render(qualifiedNames: List<String>): String =
        qualifiedNames.sorted().joinToString(separator = "\n", postfix = "\n")
}
