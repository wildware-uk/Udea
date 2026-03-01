### 🧩 ECS Framework (Fleks)
Udea uses [Fleks](https://github.com/Quillraven/Fleks) for its Entity Component System. 

#### Components
Components are data classes that implement `Component<T>`.

```kotlin
@Serializable
@UdeaNetworked
data class MyComponent(
    @UdeaSync
    var value: Float = 0f
) : Component<MyComponent> {
    override fun type() = MyComponent
    
    // Optional companion object for networking and dependencies
    companion object : UdeaComponentType<MyComponent>(
        dependsOn = dependencies(Transform),
        networkComponent = configureNetwork(syncTick = 5)
    )
}
```

#### Systems
Systems process entities that match a specific "family" of components.

```kotlin
@UdeaSystem(runIn = [Runtime.Game])
class MySystem(
    val spriteBatch: SpriteBatch = inject()
) : IteratingSystem(
    family { all(MyComponent, Transform) }
) {
    override fun onTickEntity(entity: Entity) {
        val myComp = entity[MyComponent]
        val transform = entity[Transform]
        // Logic here
    }
}
```

#### Entity Manipulation
```kotlin
// Spawning an entity
val entity = world.entity {
    it += Transform(x = 10f, y = 20f)
    it += MyComponent(value = 5f)
}

// Updating an entity
entity.configure {
    it += NewComponent()
    it -= OldComponent
}

// Accessing components
val transform = entity[Transform]
val myComp = entity.getOrNull(MyComponent)
```
