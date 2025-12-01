package dev.wildware.udea.example.component

import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.configureNetwork
import dev.wildware.udea.network.UdeaNetworked
import kotlinx.serialization.Serializable

@Serializable
@UdeaNetworked
class Team(
    var teamId: Int = NoTeam
) : Component<Team> {
    override fun type() = Team

    override fun equals(other: Any?): Boolean {
        return other is Team && teamId == other.teamId
    }

    companion object : UdeaComponentType<Team>(
        networkComponent = configureNetwork()
    ) {
        // TODO you know this should be assets!
        val NoTeam = -1
        val OrcTeam = 0
        val SoldierTeam = 1
        val UndeadTeam = 2
    }
}
