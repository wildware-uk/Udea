package dev.wildware.udea

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.actions.CreateFromTemplateAction
import com.intellij.json.JsonLanguage
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import androidx.compose.ui.awt.ComposePanel
import io.kanro.compose.jetbrains.expui.theme.DarkTheme
import dev.wildware.udea.editors.NewObjectBuilder
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.createInstance

class UdeaAssetAction :
    CreateFromTemplateAction<PsiFile>("Udea Asset", "Create a new Udea asset", AllIcons.FileTypes.Json) {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        val classes = findClassesOfType(project, AssetType::class.java.name)
            .map { it.parent as PsiClass }

        builder
            .setTitle("New Udea Asset")
            .setValidator(object : InputValidatorEx {
                override fun getErrorText(inputString: String?): String? {
                    if (inputString.isNullOrBlank()) return "Name cannot be empty"
                    return null
                }

                override fun checkInput(inputString: String?): Boolean = !inputString.isNullOrBlank()
                override fun canClose(inputString: String?): Boolean = !inputString.isNullOrBlank()
            })

        classes.forEach {
            builder.addKind(camelCaseToTitle(it.name!!), UdeaIcons.Blueprint, it.qualifiedName!!)
        }
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String {
        return "Create Udea Asset $newName"
    }

    override fun createFile(name: String, templateName: String, directory: PsiDirectory): PsiFile {
        val fileName = "$name.udea"
        val psiClass = findClassByName(directory.project, templateName)
        val classLoader = ProjectClassLoaderManager.getInstance(directory.project).classLoader
        val clazz = classLoader.loadClass(templateName).kotlin

        val dialog = object : DialogWrapper(directory.project) {
            init {
                title = "Create $name Asset"
                init()
            }

            override fun createCenterPanel() = ComposePanel().apply {
                setContent {
                    DarkTheme {
                        NewObjectBuilder(clazz)
                    }
                }
            }
        }

        if (!dialog.showAndGet()) {
            throw IllegalStateException("Dialog cancelled")
        }


        val assetType = clazz.companionObjectInstance as AssetType<out Any>
        val asset = Asset(name, clazz.createInstance(), assetType)

        val json = Json.withClassLoader(classLoader)
            .toJson(asset)

        val file = PsiFileFactory.getInstance(directory.project).createFileFromText(
            fileName,
            JsonLanguage.INSTANCE,
            json,
        )

        WriteCommandAction.runWriteCommandAction(directory.project) {
            directory.add(file)
        }

        return directory.findFile(fileName) ?: throw IllegalStateException("Failed to create file: $fileName")
    }

    private fun findClassesOfType(project: Project, baseClassName: String): List<PsiClass> {
        return runReadAction {
            val psiClass =
                JavaPsiFacade.getInstance(project).findClass(baseClassName, GlobalSearchScope.allScope(project))
            val classes = ClassInheritorsSearch.search(psiClass!!, GlobalSearchScope.allScope(project), true)
            classes.toList()
        }
    }

    private fun findClassByName(project: Project, qualifiedName: String): PsiClass? {
        return runReadAction {
            JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.allScope(project))
        }
    }
}

object UdeaAssetTemplates {
    fun generate(type: String, name: String): String {
        return when (type) {
            "Character" -> """{ "name": "$name", "type": "Character", "hp": 100 }"""
            "Item" -> """{ "name": "$name", "type": "Item", "value": 10 }"""
            "Scene" -> """{ "name": "$name", "type": "Scene", "objects": [] }"""
            else -> """{ "name": "$name", "type": "$type" }"""
        }
    }
}
