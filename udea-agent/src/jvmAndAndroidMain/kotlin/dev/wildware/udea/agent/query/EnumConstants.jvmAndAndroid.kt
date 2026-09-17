package dev.wildware.udea.agent.query

internal actual fun enumConstantsOf(value: Enum<*>): List<Enum<*>>? =
    value.declaringJavaClass.enumConstants.asList()
