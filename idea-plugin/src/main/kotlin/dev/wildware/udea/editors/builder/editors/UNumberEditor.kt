package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.util.Alarm
import dev.wildware.udea.editors.builder.UValueBuilder
import javax.swing.JLabel

@UdeaEditor(Int::class)
class UNumberEditor : UEditor<UValueBuilder> {
    override fun Panel.buildEditor(project: Project, builder: UValueBuilder, onSave: () -> Unit) {
        row {
            intTextField()
                .bindIntText(getter = { (builder.value as? Int) ?: 0}, setter = {})
                .onChanged { builder.value = it.text.toIntOrNull() ?: 0; onSave() }
                .columns(16)
        }
    }
}
