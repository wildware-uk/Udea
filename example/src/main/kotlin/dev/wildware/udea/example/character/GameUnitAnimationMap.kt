package dev.wildware.udea.example.character

import dev.wildware.udea.assets.CharacterAnimationMap
import dev.wildware.udea.dsl.CreateDsl

@CreateDsl(name = "gameUnitAnimations")
class GameUnitAnimationMap(
    walk: String,
    run: String,
    idle: String,
    death: String,
    val attack: String,
    val hit: String,
) : CharacterAnimationMap(walk, run, idle, death)
