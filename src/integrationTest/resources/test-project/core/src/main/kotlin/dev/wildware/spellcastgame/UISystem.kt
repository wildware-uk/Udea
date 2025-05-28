package dev.wildware.spellcastgame

import com.github.quillraven.fleks.IntervalSystem
import dev.wildware.spellcastgame.spell.SpellHolderComponent

class UISystem : IntervalSystem() {
    override fun onTick() {
        gameScreen.localPlayer?.let {
            gameScreen.spellHotbar.selectedSpell = it[SpellHolderComponent].selectedIndex
            gameScreen.spellHotbar.spells = it[SpellHolderComponent].spells
        }
    }
}
