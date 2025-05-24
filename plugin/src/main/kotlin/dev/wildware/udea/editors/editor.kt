package dev.wildware.udea.editors

import androidx.compose.runtime.Composable
import com.intellij.openapi.project.Project
import dev.wildware.udea.Vector2
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.EntityDefinition
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.Sprite
import dev.wildware.udea.assets.UClass
import kotlin.reflect.KClass

data class EditorType<T : Any>(
    val type: KClass<out T>,
    val generics: List<EditorType<*>> = emptyList(),
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
        registerEditor(Sprite::class, SpriteSelector)
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
