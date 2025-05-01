package dev.wildware.udea

import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementFactory
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetRefence
import dev.wildware.udea.assets.Binding
import dev.wildware.udea.assets.Control
import dev.wildware.udea.editors.EditorType

class UtilTest : UdeaTestBase() {

    fun testToJvmQualifiedName() {
        val psiClass = findClassByName(project, Binding.BindingInput.Mouse::class.qualifiedName!!)
        val jvmQualifiedName = psiClass?.toJvmQualifiedName()

        assertEquals("dev.wildware.udea.assets.Binding\$BindingInput\$Mouse", jvmQualifiedName)
    }

    fun testToEditorType_listType() {
        val project = myFixture.project
        val factory = PsiElementFactory.getInstance(project)

        // Create the List<Asset> PsiClassType
        val javaListClass = "java.util.List"
        val assetClass = "dev.wildware.udea.assets.Asset"
        val listWithAssetTypeText = "$javaListClass<$assetClass>"
        val listAssetType = factory.createTypeFromText(listWithAssetTypeText, null) as PsiClassType

        val type = listAssetType.toEditorType<List<*>>()

        assertEquals(type, EditorType(
            List::class,
            listOf(Asset::class)
        ))
    }

    fun testEditorType_assetRefType() {
        val project = myFixture.project
        val factory = PsiElementFactory.getInstance(project)

        // Create the AssetRefence PsiClassType
        val assetRefTypeText = "dev.wildware.udea.assets.AssetRefence<dev.wildware.udea.assets.Control>"
        val assetRefType = factory.createTypeFromText(assetRefTypeText, null) as PsiClassType

        val type = assetRefType.toEditorType<AssetRefence<*>>()

        assertEquals(type, EditorType(
            AssetRefence::class,
            listOf(Control::class)
        ))
    }
}