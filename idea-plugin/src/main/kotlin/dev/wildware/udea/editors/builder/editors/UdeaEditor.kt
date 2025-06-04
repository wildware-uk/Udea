package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import dev.wildware.udea.editors.builder.UBuilder
import kotlin.reflect.KClass

annotation class UdeaEditor(val value: KClass<out Any>)

interface UEditor<in T : UBuilder> {
    fun Panel.buildEditor(project: Project, builder: T, onSave: () -> Unit)
}

