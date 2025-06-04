package dev.wildware.udea.editors.builder

import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElementFactory
import dev.wildware.udea.UdeaTestBase
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.Binding
import dev.wildware.udea.assets.Control
import dev.wildware.udea.ecs.SyncStrategy
import dev.wildware.udea.editors.EditorType

class UObjectBuilderTest : UdeaTestBase() {

    fun testPsiTypeToUBuilder() {
        val factory = PsiElementFactory.getInstance(project)

        val assetRefTypeText = "dev.wildware.udea.assets.AssetReference<dev.wildware.udea.assets.Binding>"
        val assetRefType = factory.createTypeFromText(assetRefTypeText, null) as PsiClassType

        val builder = assetRefType.toUBuilder(project)
        val expected =
            USelectBuilder(EditorType(AssetReference::class, listOf(EditorType(Binding::class))), emptyList())
        assertEquals(expected, builder)
    }

    fun testPsiTypeToUBuilder2() {
        val factory = PsiElementFactory.getInstance(project)

        val assetRefTypeText = "dev.wildware.udea.assets.Binding"
        val assetRefType = factory.createTypeFromText(assetRefTypeText, null) as PsiClassType

        val builder = assetRefType.toUBuilder(project)
        val expected = USelectBuilder(EditorType(Binding::class), emptyList())
        assertEquals(expected, builder)
    }

    fun testPsiTypeToUBuilder3() {
        val factory = PsiElementFactory.getInstance(project)

        val assetRefTypeText = "dev.wildware.udea.assets.Binding.BindingInput"
        val assetRefType = factory.createTypeFromText(assetRefTypeText, null) as PsiClassType

        val builder = assetRefType.toUBuilder(project)
        val expected = USelectBuilder(EditorType(Binding.BindingInput::class), emptyList())
        assertEquals(expected, builder)
    }

    fun testPsiTypeToUBuilder_withInstance() {
        val factory = PsiElementFactory.getInstance(project)

        val assetRefTypeText = "dev.wildware.udea.assets.Binding"
        val assetRefType = factory.createTypeFromText(assetRefTypeText, null) as PsiClassType

        val instance = Binding(
            AssetReference("assets/test.json"),
            Binding.BindingInput.Key(25)
        )

        val builder = assetRefType.toUBuilder(project, instance)
        val expected = UObjectBuilder(
            EditorType(Binding::class),
            mutableMapOf(
                "control" to UObjectBuilder(
                    EditorType(AssetReference::class, listOf(EditorType(Control::class))),
                    mutableMapOf("path" to UValueBuilder(EditorType(String::class), "assets/test.json")),
                    AssetReference<Control>("assets/test.json")
                ),
                "input" to UAbstractClassBuilder(
                    EditorType(Binding.BindingInput::class),
                    mutableMapOf(
                        "dev.wildware.udea.assets.Binding.BindingInput.Key" to UObjectBuilder(
                            EditorType(Binding.BindingInput.Key::class),
                            mutableMapOf("key" to UValueBuilder(EditorType(Int::class), 25)),
                            Binding.BindingInput.Key(25)
                        ),
                        "dev.wildware.udea.assets.Binding.BindingInput.Mouse" to UObjectBuilder(
                            EditorType(Binding.BindingInput.Mouse::class),
                            mutableMapOf("button" to UValueBuilder(EditorType(Int::class), null)),
                            null
                        )
                    ),
                    "dev.wildware.udea.assets.Binding.BindingInput.Key"
                )
            ),
            instance
        )
        assertEquals(expected, builder)
    }

    fun testPsiTypeToUBuilder5() {
        val factory = PsiElementFactory.getInstance(project)

        val assetRefTypeText = SyncStrategy::class.qualifiedName!!
        val assetRefType = factory.createTypeFromText(assetRefTypeText, null) as PsiClassType

        val builder = assetRefType.toUBuilder(project, SyncStrategy.All)
        val expected = USelectBuilder(
            EditorType(SyncStrategy::class),
            listOf(SyncStrategy.All, SyncStrategy.Create, SyncStrategy.Update),
            SyncStrategy.All
        )

        assertEquals(expected, builder)
    }

    fun testBuildUObjectBuilder() {
        val builder = UObjectBuilder(
            EditorType(Binding::class),
            mutableMapOf(
                "control" to UObjectBuilder(
                    EditorType(AssetReference::class, listOf(EditorType(Control::class))),
                    mutableMapOf("path" to UValueBuilder(EditorType(String::class), "assets/test.json")),
                    AssetReference<Control>("assets/test.json")
                ),
                "input" to UAbstractClassBuilder(
                    EditorType(Binding.BindingInput::class),
                    mutableMapOf(
                        "dev.wildware.udea.assets.Binding.BindingInput.Key" to UObjectBuilder(
                            EditorType(Binding.BindingInput.Key::class),
                            mutableMapOf("key" to UValueBuilder(EditorType(Int::class), 25)),
                            Binding.BindingInput.Key(25)
                        )
                    ),
                    "dev.wildware.udea.assets.Binding.BindingInput.Key"
                )
            ),
            Binding(AssetReference("assets/test.json"), Binding.BindingInput.Key(25))
        )

        val actual = builder.build() as Binding
        assertEquals(Binding(AssetReference("assets/test.json"), Binding.BindingInput.Key(25)), actual)
    }
}
