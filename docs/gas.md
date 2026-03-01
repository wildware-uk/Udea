### ⚔️ Gameplay Ability System (GAS)
Manage complex interactions through Attributes, Gameplay Effects, and Abilities.

#### 📊 Attributes
Define attribute sets to store numeric values.

```kotlin
@UdeaNetworked
class MyAttributeSet : AttributeSet() {
    val health = attribute("health", 100f) {
        max = value(100f)
        min = value(0f)
    }
}
```

#### ✨ Gameplay Effects (GE)
Create data-driven modifiers that change attributes.

```kotlin
// Example Gameplay Effect (DSL)
gameplayEffect("damage/fire") {
    target = MyAttributeSet::health
    modifierType = Additive
    magnitude = value(-10f)
    effectDuration = instant()
    tags = list(Tags.Debuff.Fire)
}

// Applying a GE
val abilities = entity[Abilities]
val fireDamage = GameplayEffectSpec(reference("damage/fire"))
abilities.applyGameplayEffectToSelf(entity, fireDamage)
```

#### 🛡️ Abilities
Define scriptable logic for character actions.

```kotlin
class FireballAbility : AbilityExec() {
    context(world: World, spec: AbilitySpec)
    override fun activate() {
        commitAbility() // deduct mana/cooldown
        // Spawn fireball entity, play animations, etc.
        endAbility()
    }
}

// Granting and using an ability
val spec = AbilitySpec(reference("ability/fireball"))
entity[Abilities].grantAbility(entity, spec)

// Activate by tag
entity[Abilities].findAvailableAbilityWithTags(Tags.Ability.Fireball)?.activate(entity)
```

#### 🏷️ Gameplay Tags
Use hierarchical labels to query state.
```kotlin
if (abilities.hasGameplayEffectTag(Tags.State.Stunned)) {
    // Cannot move!
}
```
