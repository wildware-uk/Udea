package dev.wildware.udea.assets

import com.badlogic.gdx.scenes.scene2d.Stage
import dev.wildware.udea.dsl.CreateDsl

@CreateDsl(name = "ui")
data class UI(
    val stage: Stage.() -> Unit
) : Asset()
