package dev.wildware.udea.assets

import dev.wildware.udea.assets.dsl.ListBuilder
import dev.wildware.udea.assets.dsl.UdeaDsl

/**
 * A list that is evaluated lazily.
 * */
data class LazyList<T>(
    val listBuilder: ListBuilder<T>.() -> Unit
) {
    /**
     * Returns the list of items defined in the lazy list.
     * */
    fun get()= ListBuilder<T>()
        .apply(listBuilder)
        .build()

    /**
     * Convenience operator to invoke the lazy list.
     * */
    operator fun invoke() = this.get()
}

/**
 * Creates a new lazy list.
 * */
@UdeaDsl
fun <T> lazy(listBuilder: ListBuilder<T>.() -> Unit) =
    LazyList(
        listBuilder
    )

/**
 * Creates an empty lazy list.
 * */
fun <T> emptyLazyList() = LazyList<T> {}

/**
 * Returns a lazy list with predefined elements.
 * */
fun <T> lazyListOf(vararg elements: T) = LazyList { elements.forEach { add(it) } }

/**
 * Returns a lazy list with predefined elements.
 * */
fun <T> Collection<T>.toLazyList() = LazyList { forEach { add(it) } }
