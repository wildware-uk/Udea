package dev.wildware.udea.example.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.dsl.CreateDsl

@CreateDsl(name = "npc")
class NPC(
    val attackAnimation: String,
    val hitAnimation: String,
    val deathAnimation: String
) : Component<NPC> {
    var isDead: Boolean = false
    var touchingEnemiesCount: Int = 0

    override fun type() = NPC

    companion object : ComponentType<NPC>()
}
