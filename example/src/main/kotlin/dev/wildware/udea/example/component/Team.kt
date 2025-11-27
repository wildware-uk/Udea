package dev.wildware.udea.example.component

import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.configureNetwork
import dev.wildware.udea.network.UdeaNetworked
import kotlinx.serialization.Serializable

@Serializable
@UdeaNetworked
class Team(
    var teamId: Int
) : Component<Team> {
    override fun type() = Team

    companion object : UdeaComponentType<Team>(
        networkComponent = configureNetwork()
    ) {
        val OrcTeam = 0
        val SoldierTeam = 1
    }
}
