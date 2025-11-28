package dev.wildware.udea.example.ability

import dev.wildware.udea.ability.GameplayTag

enum class Debuffs : GameplayTag {
    Stunned
}

enum class Damage : GameplayTag {
    Physical, Magic, True
}

enum class Data : GameplayTag {
    Damage,
    Knockback,
    Duration,
    ManaCost,
    Cost,
    Heal,
    Cooldown
}

enum class Cost : GameplayTag {
    Health, Mana
}

/**
 * Represents slots an ability can be bound to
 * IE A = Left Click
 * B = Right Click
 * e.t.c
 * */
enum class Slot : GameplayTag {
    A, B, C, D, E, F, G, H
}

/**
 * A list of hints that can be used by AI to make decisions.
 * */
enum class AIHint : GameplayTag {
    Heal,
    TargetEnemy,
    TargetFriendly,
    AOE,
    Damage,
    AimEnemy,
    AimFriendly,
}
