package dev.wildware.spellcastgame

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.assets.Assets
import dev.wildware.Control
import dev.wildware.ability.Ability
import dev.wildware.ecs.component.Controller
import dev.wildware.ecs.component.Networkable
import dev.wildware.ecs.component.PlayerController
import dev.wildware.ecs.component.RigidBodyComponent
import dev.wildware.ecs.system.AbilitySystem
import dev.wildware.game
import dev.wildware.hasAuthority
import dev.wildware.spellcastgame.spell.SpellHolderComponent

class PlayerControllerSystem : IteratingSystem(
    family = family { all(RigidBodyComponent, PlayerController, Controller) }
) {
    val left = Assets[Control]["left"]
    val right = Assets[Control]["right"]
    val jump = Assets[Control]["jump"]
    val castSpell = Assets[Control]["cast_spell"]

    val selectSpell1 = Assets[Control]["select_spell_1"]
    val selectSpell2 = Assets[Control]["select_spell_2"]
    val selectSpell3 = Assets[Control]["select_spell_3"]
    val selectSpell4 = Assets[Control]["select_spell_4"]
    val selectSpell5 = Assets[Control]["select_spell_5"]
    val selectSpell6 = Assets[Control]["select_spell_6"]
    val selectSpell7 = Assets[Control]["select_spell_7"]
    val selectSpell8 = Assets[Control]["select_spell_8"]
    val selectSpell9 = Assets[Control]["select_spell_9"]
    val selectSpell0 = Assets[Control]["select_spell_10"]

    override fun onTickEntity(entity: Entity) {
        val controller = entity[Controller]
        val rigidBody = entity[RigidBodyComponent]

        if (controller.isPressed(left)) {
            rigidBody.body.applyLinearImpulse(-.1F, 0F, 0F, 0F, true)
        }

        if (controller.isPressed(right)) {
            rigidBody.body.applyLinearImpulse(.1F, 0F, 0F, 0F, true)
        }

        if (controller.isPressed(jump)) {
            rigidBody.body.applyLinearImpulse(0F, 1.0F, 0F, 0F, true)
        }

        val spellHolder = entity[SpellHolderComponent]

        when {
            controller.isJustPressed(selectSpell1) -> spellHolder.selectedIndex = 0
            controller.isJustPressed(selectSpell2) -> spellHolder.selectedIndex = 1
            controller.isJustPressed(selectSpell3) -> spellHolder.selectedIndex = 2
            controller.isJustPressed(selectSpell4) -> spellHolder.selectedIndex = 3
            controller.isJustPressed(selectSpell5) -> spellHolder.selectedIndex = 4
            controller.isJustPressed(selectSpell6) -> spellHolder.selectedIndex = 5
            controller.isJustPressed(selectSpell7) -> spellHolder.selectedIndex = 6
            controller.isJustPressed(selectSpell8) -> spellHolder.selectedIndex = 7
            controller.isJustPressed(selectSpell9) -> spellHolder.selectedIndex = 8
            controller.isJustPressed(selectSpell0) -> spellHolder.selectedIndex = 9
        }

        if (world.hasAuthority(entity) && controller.isJustPressed(castSpell)) {
            world.system<AbilitySystem>().activateAbility(entity, Assets[Ability]["spell"])
        }
    }
}
