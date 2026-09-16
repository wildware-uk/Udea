package dev.wildware.udea.core.module

import kotlin.reflect.KClass

/**
 * The fully qualified name a system manifest and an ordering failure print for a class.
 *
 * On the JVM and Android it is the binary name, `Outer$Inner`, exactly what the manifest printed
 * before `udea-core` was multiplatform (issue #203), so a golden file or a tool that compares
 * against `SomeSystem::class.java.name` is unaffected. Elsewhere there is no binary name, and it
 * is Kotlin's qualified name, `Outer.Inner`. Only ever displayed or compared as text: system order
 * is decided by registration index, never by this string.
 */
internal expect val KClass<*>.runtimeName: String
