package dev.wildware.udea.assets.compiler

/**
 * The `-jvm-target` this module hands to the two compilers it embeds.
 *
 * `udea-assets-compiler` runs the Kotlin compiler twice at build time: once as a scripting host
 * for `.udea.kts` bundles (`UdeaAssetScriptConfiguration`) and once over the transpiled sources
 * (`TranspiledAssetLoader.compile`). Both compile *against this module's own classpath*, so the
 * target they are given has to be the bytecode level that classpath was built at. When it is
 * lower, the compiler refuses to inline and the whole thing fails with ten copies of
 *
 *     Cannot inline bytecode built with JVM target 21 into bytecode that is being built
 *     with JVM target 17. Specify proper '-jvm-target' option.
 *
 * which is what `AccessorCompilationTest` reported on the JDK 21 move (issue #186). Both call
 * sites had the literal `"17"` and neither knew the toolchain had moved.
 *
 * So it is one constant with a test behind it: `EmbeddedJvmTargetTest` reads the class-file major
 * version out of this module's own compiled bytecode and requires this string to name it. A
 * future toolchain move makes that test red instead of making a build-time compile fail somewhere
 * else with a message about inlining.
 *
 * `internal`, because no caller outside this module embeds a compiler.
 */
internal const val EMBEDDED_JVM_TARGET: String = "21"
