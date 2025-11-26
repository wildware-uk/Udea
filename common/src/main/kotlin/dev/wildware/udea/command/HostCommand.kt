package dev.wildware.udea.command

import dev.wildware.udea.WorldSource
import dev.wildware.udea.gameScreen

@UdeaCommand
object HostCommand : Command {
    override val name: String = "host"

    override fun execute(args: List<String>) {
        gameScreen.initializeWorldSource(WorldSource.Host())
    }
}
