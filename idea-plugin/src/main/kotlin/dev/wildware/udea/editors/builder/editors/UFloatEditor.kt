package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.util.Alarm
import dev.wildware.udea.editors.builder.UValueBuilder
import javax.swing.JLabel

@UdeaEditor(Float::class)
class UFloatEditor : UEditor<UValueBuilder> {
    override fun Panel.buildEditor(project: Project, builder: UValueBuilder, onSave: () -> Unit) {
        row {
            textField()
                .bindText(getter = { (builder.value as? Float)?.toString() ?: "" }, setter = {})
                .onChanged { builder.value = it.text.toFloatOrNull() ?: 0F; onSave() }
                .columns(16)
        }
    }
}
