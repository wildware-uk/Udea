package dev.wildware.udea.editors.builder.editors.assets

import com.github.quillraven.fleks.Component
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.panel
import dev.wildware.udea.*
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.EntityDefinition
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.editors.builder.UListBuilder
import dev.wildware.udea.editors.builder.UObjectBuilder
import dev.wildware.udea.editors.builder.editors.UFormEditors
import dev.wildware.udea.editors.builder.editors.UObjectEditor
import dev.wildware.udea.editors.builder.editors.UdeaEditor
import dev.wildware.udea.editors.builder.toUBuilder
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel.SINGLE_SELECTION
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.createInstance

@UdeaEditor(EntityDefinition::class)
class UEntityDefinitionEditor(
    project: Project,
    builder: UObjectBuilder,
    onSave: () -> Unit = {}
) : UObjectEditor(project, builder, onSave) {

    val jbList = JBList<UObjectBuilder>().apply {
        cellRenderer = ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
            JPanel(MigLayout("ins 0, fill")).apply {
                isOpaque = true
                if (isSelected) {
                    background = list.selectionBackground
                    foreground = list.selectionForeground
                } else {
                    background = list.background
                    foreground = list.foreground
                }
                add(JBLabel(value.type.type.simpleName?.camelCaseToTitle() ?: "Unknown Component"), "push")
                add(JButton("", AllIcons.Actions.GC).apply {
                    addActionListener {
                        val result = JBPopupFactory.getInstance()
                            .createConfirmation(
                                "Delete Component",
                                "Are you sure you want to delete this component?",
                                "Other",
                                {
                                    val components = builder.get<UListBuilder>("components")
                                    components.children.remove(value)
                                    reloadAll()
                                    onSave()
                                },
                                { },
                                0
                            )
                        result.showInFocusCenter()
                    }
                }, "al right")
            }
        }

        selectionMode = SINGLE_SELECTION

        addListSelectionListener {
            if (!it.valueIsAdjusting) return@addListSelectionListener
            showComponent(selectedValue)
        }
    }

    val componentPanel = JPanel(BorderLayout())

    override val onSave = {
        reloadAll()
        onSave()
    }

    override fun createEditor(): JComponent {
        reloadAll()

        return ScrollPaneFactory.createScrollPane(
            JPanel(MigLayout()).apply {
                add(JBLabel("Components"), "wrap, width 100%, gapbottom 5")
                add(JButton("Add Component").apply {
                    addActionListener {
                        showComponentTypeMenu(project) { componentType ->
                            val newComponent = componentType.createInstance()

                            val children = builder.get<UListBuilder>("components").children

                            val dependencies = if (componentType.companionObjectInstance is UdeaComponentType<*>) {
                                (componentType.companionObjectInstance as UdeaComponentType<*>).dependsOn.dependencies
                                    .filter { dep -> children.all { it.type.type != dep::class } }.map {
                                        it::class.java.enclosingClass.kotlin.createInstance()
                                    }.toTypedArray()
                            } else emptyArray()

                            children.apply {
                                add(newComponent.toUBuilder(project))

                                dependencies.forEach {
                                    add(it.toUBuilder(project))
                                }
                            }

                            onSave()

                            reloadAll()
                        }
                    }
                }, "width 120!, gapbottom 10, wrap")
                add(jbList, "width 100%, wrap")
                add(componentPanel, "width 100%")
            },
            VERTICAL_SCROLLBAR_AS_NEEDED,
            HORIZONTAL_SCROLLBAR_NEVER, true
        )
    }

    fun reloadAll() {
        reloadComponentList((builder.children["components"] as UListBuilder).children as List<UObjectBuilder>)
    }

    private fun reloadComponentList(components: List<UObjectBuilder>) {
        jbList.setListData(components.toTypedArray())
    }

    private fun showComponent(builder: UObjectBuilder) {
        println("Rendering $builder")

        with(UFormEditors.getEditor(builder)) {
            componentPanel.removeAll()
            componentPanel.add(panel {
                panel {
                    buildEditor(project, builder, onSave)
                }
            })
            componentPanel.revalidate()
            componentPanel.repaint()
        }
    }

    fun showComponentTypeMenu(project: Project, onComponentSelected: (KClass<Component<*>>) -> Unit) {
        // Retrieve the list of component types
        val componentTypes = pooledResult {
            findClassesOfType(project, Component::class.java.name)
                .map { project.service<ProjectClassLoaderManager>().classLoader.loadClass(it.toJvmQualifiedName()).kotlin }
                .sortedBy { it.simpleName }
        }

        // Create a popup and show it
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(componentTypes)
            .setSelectionMode(SINGLE_SELECTION)
            .setRenderer({ list, value, index, isSelected, cellHasFocus ->
                JBLabel(
                    value.simpleName?.camelCaseToTitle() ?: "Unknown Component"
                )
            })
            .setTitle("Select Component Type")
            .setItemChosenCallback { selectedValue ->
                @Suppress("UNCHECKED_CAST")
                onComponentSelected(selectedValue as KClass<Component<*>>)
            }
            .createPopup()
            .showInFocusCenter()
    }
}

class ComponentRow : JComponent() {

}