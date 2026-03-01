### 🚀 Getting Started with Udea
Follow these steps to create a simple game world.

#### 1. Define your Level
The map and starting assets.

```kotlin
// script/assets/level1.udea.kts
level("level1") {
    map = reference("map/main")
    startingAbilities = list(reference("ability/passive_regen"))
}
```

#### 2. Initialize the Engine
Subclass `UdeaGameManager` to provide your configuration and systems.

```kotlin
class MyGame : UdeaGameManager() {
    override fun create() {
        super.create()
        // Register your ECS systems
        registerSystem<MyGameplaySystem>()
        registerSystem<SoundSystem>()
        
        // Load the level
        setLevel(reference("level1"))
    }
}
```

#### 3. Create a Character Blueprint
A template for your player character.

```kotlin
// script/assets/hero.udea.kts
blueprint("character/hero") {
    component(Transform())
    component(Body(type = DynamicBody))
    component(CharacterController(moveSpeed = 10f))
    component(Abilities())
    component(Attributes(MyAttributeSet()))
}
```

#### 4. Spawn Entities
Instantiate game objects using references.

```kotlin
val heroRef = reference<Blueprint>("character/hero")
heroRef.value.newInstance(world) { entity ->
    entity[Transform].position.set(100f, 100f)
}
```

#### 5. Handle Movement
Use the `CharacterController` component to move your characters.

```kotlin
// In your input system
fun update(delta: Float) {
    val player = world.family { all(Networkable) }.firstOrNull { it[Networkable].owner == myClientId }
    player?.let { entity ->
        val controller = entity[CharacterController]
        controller.movement.set(Gdx.input.isKeyPressed(Keys.D).toInt() - Gdx.input.isKeyPressed(Keys.A).toInt(),
                                Gdx.input.isKeyPressed(Keys.W).toInt() - Gdx.input.isKeyPressed(Keys.S).toInt())
    }
}
```
