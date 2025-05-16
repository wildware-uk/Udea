package dev.wildware.udea.config

import dev.wildware.udea.Json

data class GameConfig(
    val defaultLevel: String
)

val gameConfig = Json.fromJson<GameConfig>(
    GameConfig::class.java
        .getResourceAsStream("game.json")
        ?: error("Could not find game.json")
)
