package dev.wildware.udea.core.module

import kotlin.reflect.KClass

/** A local or anonymous class has no qualified name, and its `toString()` is the best left. */
internal actual val KClass<*>.runtimeName: String get() = qualifiedName ?: toString()
