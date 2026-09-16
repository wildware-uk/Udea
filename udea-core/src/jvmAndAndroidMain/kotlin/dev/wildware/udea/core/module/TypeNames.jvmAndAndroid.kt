package dev.wildware.udea.core.module

import kotlin.reflect.KClass

internal actual val KClass<*>.runtimeName: String get() = java.name
