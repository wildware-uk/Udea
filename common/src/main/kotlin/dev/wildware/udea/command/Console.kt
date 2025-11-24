package dev.wildware.udea.command

import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import ktx.scene2d.Scene2DSkin

class Console(
    private val skin: Skin = Scene2DSkin.defaultSkin,
    private val maxLines: Int = 50
) : Table(skin) {

    private val logLines = ArrayDeque<String>()
    private val label = Label("", skin).apply {
        setWrap(true)
    }
    private val commandInput = TextField("", skin).apply {
        messageText = "Enter command..."
    }

    init {
        pad(5f)
        add(label).grow().left().top().row()
        add(commandInput).growX().padTop(5f)
    }

    fun println(text: String) {
        logLines.addLast(text)
        if (logLines.size > maxLines) logLines.removeFirst()
        updateLabel()
    }

    fun clearConsole() {
        logLines.clear()
        updateLabel()
    }

    private fun updateLabel() {
        label.setText(logLines.joinToString("\n"))
    }

    fun onCommand(handler: (String) -> Unit) {
        commandInput.setTextFieldListener { _, c ->
            if (c == '\r' || c == '\n') {
                handler(commandInput.text)
                commandInput.text = ""
            }
        }
    }
}
