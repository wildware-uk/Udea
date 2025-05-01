package dev.wildware.udea.assets

import com.intellij.openapi.components.service
import dev.wildware.udea.Json
import dev.wildware.udea.UdeaTestBase
import org.intellij.lang.annotations.Language
import java.lang.Thread.sleep

class AssetManagerTest : UdeaTestBase() {

    lateinit var assetManager: AssetManager

    override fun setUp() {
        super.setUp()
        assetManager = project.service<AssetManager>()
    }

    fun testAssetManagerLoading() {
        myFixture.copyDirectoryToProject("testAssets", "testAssets")

        assetManager.reloadAssets()

        val assets = Assets.toList()
        assert(assets.isNotEmpty()) { "Assets should not be empty" }

        val expectedControl = Control()
        val controlAsset: Control = Assets["/src/testAssets/testControl.udea"]
        assertEquals(expectedControl, controlAsset)

        val expectedBinding = Binding(AssetRefence(controlAsset.path), Binding.BindingInput.Mouse(2))
        val bindingAsset: Binding = Assets["/src/testAssets/testBinding.udea"]
        assertEquals(expectedBinding, bindingAsset)
    }

    fun testAssetSerialization() {
        val control = Control()
        val serializedControl = Json.fromJson<Control>(Json.toJson(control))

        assertEquals(control, serializedControl)

        val binding = Binding(AssetRefence("control.udea"), Binding.BindingInput.Mouse(2))
        val serializedBinding = Json.fromJson<Binding>(Json.toJson(binding))
        assertEquals(binding, serializedBinding)
    }
}
