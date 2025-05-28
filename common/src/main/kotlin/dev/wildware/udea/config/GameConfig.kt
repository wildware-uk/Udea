package dev.wildware.udea.config

import dev.wildware.udea.Json
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.Level

data class GameConfig(
    val defaultLevel: AssetReference<Level>?
)

val gameConfig = Json.fromJson<GameConfig>(
    GameConfig::class.java
        .getResourceAsStream("/game.json")
        ?: error("Could not find game.json")
)
