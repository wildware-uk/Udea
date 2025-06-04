package dev.wildware.udea.editors.builder.editors

import dev.wildware.udea.editors.builder.UBuilder
import org.reflections.Reflections
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

object UEditors {

    private val editors: Map<Pair<KClass<*>, KClass<out UBuilder>>, KClass<UEditor<UBuilder>>> = Reflections("dev.wildware")
        .getTypesAnnotatedWith(UdeaEditor::class.java)
        .associateBy(
            { it.getAnnotation(UdeaEditor::class.java).value to (it.kotlin.supertypes.first().arguments[0].type!!.classifier as KClass<UBuilder>) },
            { it.kotlin as KClass<UEditor<UBuilder>> }
        )

    fun <T : UBuilder> getEditor(builder: T): UEditor<T> {
        return getSuperclassesOrdered(builder.type.type)
            .mapNotNull { editors[it to builder::class] }
            .firstOrNull()
            ?.createInstance() as? UEditor<UBuilder>
            ?: error("No editor found for type: ${builder.type.type.simpleName} (${builder::class.simpleName})")
    }

    private fun getSuperclassesOrdered(type: KClass<*>): Sequence<KClass<*>> = sequence {
        var current: KClass<*>? = type
        while (current != null) {
            yield(current)
            current = current.supertypes.firstOrNull()?.classifier as? KClass<*>
        }
    }
}
