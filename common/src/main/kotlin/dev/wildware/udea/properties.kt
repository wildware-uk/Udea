package dev.wildware.udea

import dev.wildware.udea.ability.AbilitySpec
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.isAccessible

/**
 * Delegate for tracking changes to a property.
 * Works best with primitive types or data classes.
 * */
class DirtyProperty<T>(
    defaultValue: T
) : ReadWriteProperty<AbilitySpec, T> {

    var dirty: Boolean = true
        private set

    private var value = defaultValue

    override fun getValue(thisRef: AbilitySpec, property: KProperty<*>) = value

    override fun setValue(thisRef: AbilitySpec, property: KProperty<*>, value: T) {
        if (this.value != value) {
            dirty = true
        }

        this.value = value
    }

    /**
     * Observing a property marks it as not dirty, and returns
     * the current value of dirty.
     * */
    fun observe(): Boolean {
        val wasDirty = this.dirty
        this.dirty = false
        return wasDirty
    }
}

/**
 * Delegates a property to be tracked for changes.
 * */
fun <T> dirty(default: T) = DirtyProperty(default)

inline fun <reified T> KProperty0<*>.delegateAs(): T {
    isAccessible = true
    return getDelegate() as T
}
