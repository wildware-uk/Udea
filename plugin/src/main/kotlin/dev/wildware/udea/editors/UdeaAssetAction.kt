package dev.wildware.udea.editors

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.actions.CreateFromTemplateAction
import com.intellij.json.JsonLanguage
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import dev.wildware.udea.Json
import dev.wildware.udea.UdeaIcons
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.camelCaseToTitle
import dev.wildware.udea.findClassesOfType
import kotlin.reflect.full.primaryConstructor

class UdeaAssetAction :
    CreateFromTemplateAction<PsiFile>("Udea Asset", "Create a new Udea asset", AllIcons.FileTypes.Json) {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        val classes = findClassesOfType(project, Asset::class.java.name)

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
            builder.addKind(it.name!!.camelCaseToTitle(), UdeaIcons.Blueprint, it.qualifiedName!!)
        }
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String {
        return "Create Udea Asset $newName"
    }

    override fun createFile(name: String, templateName: String, directory: PsiDirectory): PsiFile {
        try {
            val fileName = "$name.udea"

            var asset: Asset? = null

            if (canSkipEditor(templateName)) {
                asset = Class.forName(templateName).kotlin.primaryConstructor!!.callBy(emptyMap()) as Asset?
            }

            val assetFile = AssetFile(templateName, asset)

            val json = Json.toJson(assetFile)

            val file = PsiFileFactory.getInstance(directory.project).createFileFromText(
                fileName,
                JsonLanguage.INSTANCE,
                json,
            )

            WriteCommandAction.runWriteCommandAction(directory.project) {
                directory.add(file)
            }

            return directory.findFile(fileName)
                ?: throw IllegalStateException("Failed to create file: $fileName")
        } catch (e: Exception) {
            e.printStackTrace()
            throw IllegalStateException("Failed to create asset: $name", e)
        }
    }

    private fun canSkipEditor(templateName: String): Boolean =
        Class.forName(templateName).kotlin.primaryConstructor!!.parameters.let { it.all { it.isOptional } || it.isEmpty() }
}
