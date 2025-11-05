package dev.wildware.udea.config

import dev.wildware.udea.Json
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.Sprite

data class GameConfig(
    val defaultLevel: AssetReference<Level>?,
    val defaultCharacter: AssetReference<Blueprint>?,
    val backgroundTexture: String,
)

val gameConfig = Json.fromJson<GameConfig>(
    GameConfig::class.java
        .getResourceAsStream("/game.json")
        ?: error("Could not find game.json")
)
