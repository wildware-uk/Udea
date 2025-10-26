package dev.wildware.udea.editors

import androidx.compose.runtime.Composable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import dev.wildware.udea.Vector2
import dev.wildware.udea.assets.*
import kotlin.reflect.KClass

data class EditorType<out T : Any>(
    val type: KClass<out T>,
    val generics: List<EditorType<Any>> = emptyList(),
)

interface ComposeEditor<T : Any> {
    @Composable
    fun CreateEditor(project: Project, type: EditorType<T>, value: T?, onValueChange: (T) -> Unit)
}

object Editors {
    internal val editors = mutableMapOf<KClass<*>, ComposeEditor<*>>()

    init {
        // TODO BETTER APPROACH
        registerEditor(Level::class, LevelEditor)
        registerEditor(List::class, ListEditor)
        registerEditor(Vector2::class, Vector2Editor)
        registerEditor(Int::class, IntEditor)
        registerEditor(String::class, StringEditor)
        registerEditor(Float::class, FloatEditor)
        registerEditor(Boolean::class, BooleanEditor)
        registerEditor(AssetReference::class, AssetReferenceEditor)
        registerEditor(Enum::class, EnumEditor)
        registerEditor(UClass::class, UClassEditor)
        registerEditor(Blueprint::class, BlueprintEditor)
        registerEditor(Any::class, ReflectionEditor)
    }

    fun <T : Any> registerEditor(type: KClass<T>, editor: ComposeEditor<T>) {
        editors[type] = editor
    }

    fun <T : Any> getEditor(type: KClass<T>): ComposeEditor<T>? {
        return getEditorRaw(type) as? ComposeEditor<T>
    }

    fun getEditorRaw(type: KClass<*>): ComposeEditor<*>? {
        return (editors[type] ?: editors.entries.first { it.key.java.isAssignableFrom(type.java) }.value)
    }
}

interface UEditor<T : Any> {
    fun Panel.CreateEditor(project: Project, type: EditorType<*>, value: T?, onValueChange: (T) -> Unit)
}

object UEditors {
    internal val editors = mutableMapOf<KClass<*>, UEditor<*>>()

    init {
        // Register all UEditor implementations
        registerEditor(List::class, ListEditorSwing)
        registerEditor(Int::class, IntEditorSwing)
        registerEditor(String::class, StringEditorSwing)
        registerEditor(Float::class, FloatEditorSwing)
        registerEditor(Boolean::class, BooleanEditorSwing)
        registerEditor(Vector2::class, Vector2EditorSwing)
        registerEditor(UClass::class, UClassEditorSwing)
        registerEditor(Enum::class, EnumEditorSwing)
        registerEditor(AssetReference::class, AssetReferenceSwingEditor)
    }

    fun <T : Any> registerEditor(type: KClass<T>, editor: UEditor<*>) {
        editors[type] = editor
    }

    fun <T : Any> getEditor(type: KClass<T>): UEditor<T>? {
        return getEditorRaw(type) as UEditor<T>?
    }

    fun getEditorRaw(type: KClass<*>): UEditor<*>? {
        return (editors[type] ?: editors.entries.firstOrNull { it.key.java.isAssignableFrom(type.java) }?.value)
    }
}
