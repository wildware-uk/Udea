package dev.wildware.udea.command

import dev.wildware.udea.WorldSource
import dev.wildware.udea.gameScreen

@UdeaCommand
object ConnectCommand : Command {
    override val name: String = "connect"

    override fun execute(args: List<String>) {
        val (address) = args
        println(
            "Connecting to $address"
        )

        gameScreen.initializeWorldSource(WorldSource.Connect(address))
    }
}
