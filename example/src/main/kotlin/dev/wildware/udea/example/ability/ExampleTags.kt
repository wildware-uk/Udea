package dev.wildware.udea.example.ability

import dev.wildware.udea.ability.GameplayTag

enum class Debuffs : GameplayTag {
    Stunned
}

enum class Damage : GameplayTag {
    Physical, Magic, True
}

enum class Data : GameplayTag {
    Damage, Knockback, StunDuration
}
