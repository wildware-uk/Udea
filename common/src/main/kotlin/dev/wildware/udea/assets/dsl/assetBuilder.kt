package dev.wildware.udea.assets.dsl

import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * Dsl holder for creating a list of assets.
 * */
@UdeaDsl
class ListBuilder<T>(
    items: List<T> = emptyList()
) {
    private val items = items.toMutableList()

    fun add(item: T) {
        items.add(item)
    }

    operator fun T.unaryPlus() {
        items.add(this)
    }

    fun build(): List<T> {
        return items.toList()
    }
}

/**
 * Creates an object by constructor call, respecting default parameters.
 * */
fun <T : Any> createObject(kClass: KClass<T>, parameters: Map<String, Any?>): T {
    val constructor = kClass.primaryConstructor!!
    val parameters = parameters.mapKeys {
        constructor.parameters.find { param -> param.name == it.key }
            ?: error("No parameter found with name ${it.key}")
    }

    return constructor.callBy(parameters)
}

/**
 * Creates a list of items using a [ListBuilder].
 * */
fun <T> list(block: ListBuilder<T>.() -> Unit) =
    ListBuilder<T>().apply(block).build()
