package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import dev.wildware.udea.editors.builder.UBuilder
import dev.wildware.udea.editors.builder.UObjectBuilder
import org.reflections.Reflections
import javax.swing.JComponent
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

annotation class UdeaEditor(val value: KClass<out Any>)

/**
 * Lightweight Kotlin DSL editor for forms.
 * */
interface UFormEditor<in T : UBuilder> {
    fun Panel.buildEditor(project: Project, builder: T, onSave: () -> Unit)
}

/**
 * Swing-based editors for objects.
 * */
abstract class UObjectEditor(
    val project: Project,
    val builder: UObjectBuilder,
    open val onSave: () -> Unit = {}
) {
    abstract fun createEditor(): JComponent
}

object UFormEditors {

    private val editors: Map<Pair<KClass<*>, KClass<out UBuilder>>, KClass<UFormEditor<UBuilder>>> =
        Reflections("dev.wildware")
            .getTypesAnnotatedWith(UdeaEditor::class.java)
            .filter { it.kotlin.isSubclassOf(UFormEditor::class) }
            .associateBy(
                { it.getAnnotation(UdeaEditor::class.java).value to (it.kotlin.supertypes.first().arguments[0].type!!.classifier as KClass<UBuilder>) },
                { it.kotlin as KClass<UFormEditor<UBuilder>> }
            )

    fun <T : UBuilder> getEditor(builder: T): UFormEditor<T> {
        return getSuperclassesOrdered(builder.type.type)
            .mapNotNull { editors[it to builder::class] }
            .firstOrNull()
            ?.createInstance() as? UFormEditor<UBuilder>
            ?: error("No editor found for type: ${builder.type.type.simpleName} (${builder::class.simpleName})")
    }
}

object UObjectEditors {
    private val editors: Map<KClass<*>, KClass<UObjectEditor>> =
        Reflections("dev.wildware")
            .getTypesAnnotatedWith(UdeaEditor::class.java)
            .filter { it.kotlin.isSubclassOf(UObjectEditor::class) }
            .associateBy(
                { it.getAnnotation(UdeaEditor::class.java).value },
                { it.kotlin as KClass<UObjectEditor> }
            )

    fun <T : UBuilder> getEditor(project: Project, builder: T, onSave: () -> Unit): UObjectEditor {
        return getSuperclassesOrdered(builder.type.type)
            .mapNotNull { editors[it] }
            .firstOrNull()
            ?.primaryConstructor!!.call(project, builder, onSave) as? UObjectEditor
            ?: error("No editor found for type: ${builder.type.type.simpleName} (${builder::class.simpleName})")
    }
}


private fun getSuperclassesOrdered(type: KClass<*>): Sequence<KClass<*>> = sequence {
    var current: KClass<*>? = type
    while (current != null) {
        yield(current)
        current = current.supertypes.firstOrNull()?.classifier as? KClass<*>
    }
}