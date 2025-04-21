//package dev.wildware.udea.editors
//
//import androidx.compose.foundation.clickable
//import androidx.compose.foundation.layout.*
//import androidx.compose.foundation.lazy.LazyColumn
//import androidx.compose.foundation.lazy.items
//import androidx.compose.runtime.Composable
//import androidx.compose.runtime.MutableState
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.awt.SwingPanel
//import androidx.compose.ui.unit.dp
//import com.github.quillraven.fleks.Component
//import com.intellij.openapi.application.ApplicationManager
//import com.intellij.openapi.application.runReadAction
//import com.intellij.openapi.project.Project
//import com.intellij.psi.JavaPsiFacade
//import com.intellij.psi.PsiClass
//import com.intellij.psi.search.GlobalSearchScope
//import com.intellij.psi.search.searches.ClassInheritorsSearch
//import dev.wildware.EntityInstance
//import dev.wildware.Level
//import dev.wildware.LevelEditor
//import dev.wildware.udea.AssetEditor
//import dev.wildware.udea.ProjectClassLoaderManager
//import io.kanro.compose.jetbrains.expui.control.Label
//import io.kanro.compose.jetbrains.expui.control.OutlineButton
//import java.awt.BorderLayout
//import javax.swing.JPanel
//import kotlin.collections.isNotEmpty
//import kotlin.reflect.KClass
//import kotlin.reflect.full.createInstance
//
//@AssetEditor(assetType = Level::class)
//@Composable
//fun LevelEditorPanel(
//    project: Project,
//    level: Level,
//    onSave: (Level) -> Unit
//) {
//    val selectedEntity: MutableState<EntityInstance?> = remember { mutableStateOf(null) }
//    val componentClasses: MutableState<List<PsiClass>> = remember { mutableStateOf(emptyList()) }
//    val selectedComponent: MutableState<PsiClass?> = remember { mutableStateOf(null) }
//    val constructorParameters: MutableState<List<ParameterInfo>> = remember { mutableStateOf(emptyList()) }
//    val showComponentDropdown: MutableState<Boolean> = remember { mutableStateOf(false) }
//    val levelEditor = remember { LevelEditor(level) }
//    var level by remember { mutableStateOf(level) }
//
//    Row(modifier = Modifier.fillMaxSize()) {
//        Box(
//            modifier = Modifier
//                .weight(0.8f)
//                .fillMaxHeight()
//        ) {
//            SwingPanel(
//                factory = { JPanel(BorderLayout()).apply { add(levelEditor.getCanvas(), BorderLayout.CENTER) } },
//                modifier = Modifier.fillMaxSize()
//            )
//        }
//
//        // Second Component (Right Panel)
//        Column(
//            modifier = Modifier
//                .weight(0.2f)
//                .fillMaxHeight()
//                .padding(8.dp)
//        ) {
//            Box(
//                modifier = Modifier.fillMaxWidth()
//                    .height(360.dp)
//            ) {
//                LazyColumn {
//                    items(level.entities) { entity ->
//                        Label(
//                            text = "Entity",
//                            modifier = Modifier.clickable {
//                                selectedEntity.value = entity
//                            }
//                        )
//                    }
//                }
//            }
//
//            OutlineButton(onClick = {
//                level = level.copy(
//                    entities = level.entities + EntityInstance.Empty
//                )
//                onSave(level)
//            }) {
//                Label("Add Entity")
//            }
//
//            if (selectedEntity.value != null) {
//                OutlineButton(onClick = {
//                    val className = "com.github.quillraven.fleks.Component"
//                    // Run the slow operation in a background thread
//                    ApplicationManager.getApplication().executeOnPooledThread {
//                        val classes = findClassesOfType(project, className)
//                        // Update UI on EDT
//                        ApplicationManager.getApplication().invokeLater {
//                            componentClasses.value = classes
//                            showComponentDropdown.value = true
//                            println("Found ${classes.size} classes")
//                        }
//                    }
//                }) {
//                    Label("Add Component")
//                }
//
//                // Show dropdown when component classes are loaded
//                if (showComponentDropdown.value && componentClasses.value.isNotEmpty()) {
//                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
//                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
//                            items(componentClasses.value) { componentClass ->
//                                Label(
//                                    text = componentClass.name ?: "Unknown",
//                                    modifier = Modifier.fillMaxWidth().clickable {
//                                        selectedComponent.value = componentClass
//                                        showComponentDropdown.value = false
//
//                                        // Run the slow operation in a background thread
//                                        ApplicationManager.getApplication().executeOnPooledThread {
//                                            val params = getConstructorParameters(componentClass)
//                                            // Update UI on EDT
//                                            ApplicationManager.getApplication().invokeLater {
//                                                constructorParameters.value = params
//                                            }
//                                        }
//                                    }.padding(4.dp)
//                                )
//                            }
//                        }
//                    }
//                }
//
//                // Show constructor parameters if a component is selected
//                if (selectedComponent.value != null) {
//                    Label(
//                        text = "Selected Component: ${selectedComponent.value?.name}",
//                        modifier = Modifier.padding(top = 8.dp)
//                    )
//
//                    if (constructorParameters.value.isNotEmpty()) {
//                        Label(text = "Constructor Parameters:", modifier = Modifier.padding(top = 4.dp))
//                        LazyColumn {
//                            items(constructorParameters.value) { parameter ->
//                                Label(
//                                    text = "${parameter.name}: ${parameter.type}",
//                                    modifier = Modifier.padding(start = 8.dp, top = 2.dp)
//                                )
//                            }
//                        }
//                    } else {
//                        Label(text = "No constructor parameters", modifier = Modifier.padding(top = 4.dp))
//                    }
//
//                    // Add button to instantiate and add the component
//                    OutlineButton(
//                        onClick = {
//                            selectedComponent.value?.let { componentClass ->
//                                val className = componentClass.qualifiedName
//                                if (className != null) {
//                                    val component = instantiateComponent(project, levelEditor, className)
//                                    if (component != null) {
////                                        // Add the component to the selected entity
////                                        selectedEntity.value?.let { entityInstance ->
////                                            levelEditor.editor.levelEditor.addToEntity(entityInstance.entity, component)
////                                            onSave(level)
////                                            println("Added component ${component::class.simpleName} to entity ${entityInstance.entity.id}")
////                                        }
//                                    }
//                                }
//                            }
//                        },
//                        modifier = Modifier.padding(top = 8.dp)
//                    ) {
//                        Label("Add to Entity")
//                    }
//                }
//
//                // Show existing components
//                Label(text = "Existing Components:", modifier = Modifier.padding(top = 8.dp))
//                LazyColumn {
//                    items(selectedEntity.value!!.snapshot.components) { component ->
//                        Label("Component (${component::class.simpleName})")
//                    }
//                }
//            }
//        }
//    }
//}
//
//
//private fun findClassesOfType(project: Project, baseClassName: String): List<PsiClass> {
//    return runReadAction {
//        val psiClass = JavaPsiFacade.getInstance(project).findClass(baseClassName, GlobalSearchScope.allScope(project))
//        val classes = ClassInheritorsSearch.search(psiClass!!)
//        classes.toList()
//    }
//}
//
////private fun getConstructorParameters(psiClass: PsiClass): List<ParameterInfo> {
////    return runReadAction {
////        val constructors = psiClass.constructors
////        if (constructors.isEmpty()) {
////            emptyList()
////        } else {
////            // Use the constructor with the most parameters
////            val parameters =
////                constructors.maxByOrNull { it.parameterList.parametersCount }?.parameterList?.parameters?.toList()
////                    ?: emptyList()
////            // Convert PsiParameter to ParameterInfo to avoid accessing PSI elements outside read action
////            parameters.map { param ->
////                ParameterInfo(
////                    name = param.name ?: "",
////                    type = param.type.presentableText
////                )
////            }
////        }
////    }
////}
//
//@Suppress("UNCHECKED_CAST")
//private fun instantiateComponent(project: Project, levelEditor: LevelEditor, className: String): Component<*>? {
//    try {
//        // Get the classloader from the ProjectClassLoaderManager
//        val classLoader = ProjectClassLoaderManager.getInstance(project).classLoader
//        println("Using classloader from ProjectClassLoaderManager")
//
//        // Load the component class using the classloader
//        val componentClass = Class.forName(className, true, classLoader).kotlin as KClass<Component<*>>
//        println("Instantiating $componentClass")
//
//        // Try to create an instance
//        // For simplicity, we're only handling no-arg constructors here
//        // In a real implementation, you'd need to handle constructor parameters
//        return componentClass.createInstance()
//    } catch (e: Exception) {
//        println("Error instantiating component: ${e.message}")
//        e.printStackTrace()
//        return null
//    }
//}
