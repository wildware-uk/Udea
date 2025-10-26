package dev.wildware.udea.assets

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import dev.wildware.udea.ecs.component.UdeaClass
import dev.wildware.udea.ecs.component.UdeaClass.UClassAttribute.NoInline
import kotlin.reflect.KClass

/**
 * A reference to a class in the game engine.
 **/
@UdeaClass(NoInline)
data class UClass<out T : Any>(
    val className: String
) {
    @Suppress("UNCHECKED_CAST")
    fun toKClass(): KClass<out T> {
        return Class.forName(className).kotlin as KClass<T>
    }

    @JsonValue
    override fun toString() = className

    companion object {
        @JvmStatic
        @JsonCreator
        fun <T:Any>createUClass(className: String): UClass<T> = UClass(className)
    }
}
