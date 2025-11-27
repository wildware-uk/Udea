package dev.wildware.udea.example.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.dsl.CreateDsl

class GameUnit : Component<GameUnit> {
    var isDead: Boolean = false

    override fun type() = GameUnit

    companion object : ComponentType<GameUnit>()
}
