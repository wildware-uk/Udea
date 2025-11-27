package dev.wildware.udea.example.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.udea.dsl.CreateDsl

@CreateDsl(name = "npc")
class NPC : Component<NPC> {
    var isDead: Boolean = false

    override fun type() = NPC

    companion object : ComponentType<NPC>()
}
