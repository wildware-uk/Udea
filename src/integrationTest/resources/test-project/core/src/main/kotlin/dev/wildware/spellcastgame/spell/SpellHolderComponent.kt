package dev.wildware.spellcastgame.spell

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import dev.wildware.network.NetworkAuthority
import dev.wildware.network.NetworkComponent
import dev.wildware.network.Networked
import dev.wildware.network.SyncStrategy
import kotlinx.serialization.Serializable

@Networked
@Serializable
class SpellHolderComponent(
    val spells: List<Spell>,
    var selectedIndex: Int = 0
) : Component<SpellHolderComponent> {

    val selectedSpell: Spell?
        get() = spells.getOrNull(selectedIndex)

    override fun type() = SpellHolderComponent

    companion object : ComponentType<SpellHolderComponent>(), NetworkComponent<SpellHolderComponent> {
        override val networkAuthority = NetworkAuthority.Client
//        override val syncStrategy = SyncStrategy.Create TODO how do we sync client creates?
    }
}
