package dev.wildware.udea.core.module

import kotlin.reflect.KClass

public actual val KClass<*>.runtimeName: String get() = java.name
