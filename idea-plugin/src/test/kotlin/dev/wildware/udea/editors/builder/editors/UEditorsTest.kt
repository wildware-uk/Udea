package dev.wildware.udea.editors.builder.editors

import dev.wildware.udea.UdeaTestBase

class UEditorsTest : UdeaTestBase() {

    fun testGetEditorInt() {
        val kClass = Int::class
        val editor = UEditors.getEditor(kClass)
        assertTrue(editor is UNumberEditor)
    }

    fun testGetEditorString() {
        val kClass = String::class
        val editor = UEditors.getEditor(kClass)
        assertTrue(editor is UStringEditor)
    }

    fun testGetEditorAny() {
        class TestClass

        val kClass = TestClass::class
        val editor = UEditors.getEditor(kClass)
        assertTrue(editor is UObjectBuilder)
    }
}
