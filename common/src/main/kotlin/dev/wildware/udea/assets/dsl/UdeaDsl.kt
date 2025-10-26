package dev.wildware.udea.assets.dsl

@DslMarker
annotation class UdeaDsl

@UdeaDsl
interface UdeaDslBase<T> {
    fun build(): T
}
