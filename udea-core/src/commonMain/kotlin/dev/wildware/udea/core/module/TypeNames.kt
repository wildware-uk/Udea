package dev.wildware.udea.core.module

import kotlin.reflect.KClass

/**
 * The fully qualified name a system manifest and an ordering failure print for a class.
 *
 * On the JVM and Android it is the binary name, `Outer$Inner`, exactly what the manifest printed
 * before `udea-core` was multiplatform (issue #203), so a golden file or a tool that compares
 * against `SomeSystem::class.java.name` is unaffected. Elsewhere there is no binary name, and it
 * is Kotlin's qualified name, `Outer.Inner`. Inside this module it is only ever displayed or
 * compared as text: system order is decided by registration index, never by this string.
 *
 * Public because `udea-gas` keys its ability executors by it (issue #204), where a game names an
 * executor as `SomeExec::class.java.name` on the JVM. That use *does* order by it. For named classes
 * with identifier names the two spellings sort identically, because `$` and `.` both sort below
 * every identifier character; they disagree only where the first difference between two names is
 * a `$` in one and a `.` in the other, which takes a class whose qualified name is also a package.
 */
public expect val KClass<*>.runtimeName: String
