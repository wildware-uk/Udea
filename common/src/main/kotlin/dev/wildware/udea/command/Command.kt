package dev.wildware.udea.command

import org.reflections.Reflections
import org.reflections.scanners.Scanners
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class UdeaCommand

/**
 * Represents a console command that can be executed in-game.
 * */
interface Command {
    val name: String
    fun execute(args: List<String>)
}

object Commands {
    private val commands = Reflections(Commands::class.java)
        .getTypesAnnotatedWith(UdeaCommand::class.java)
        .map { (it.kotlin.objectInstance as? Command) ?: error("${it.simpleName}: Command must be an object") }

    init {
        println("Loaded ${commands.size} commands")
    }

    /**
     * Executes a console command.
     * */
    fun execute(command: String) {
        val args = command.split(" ")
        val cmd = commands.find { it.name == args[0] } ?: error("Command not found: $command")
        cmd.execute(args.drop(1))
    }
}
