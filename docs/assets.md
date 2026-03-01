### 📦 Asset Management & DSL
Udea favors a "code-as-data" approach. Most game objects are defined in `.udea.kts` scripts.

#### Asset References
Use `reference<T>("path/to/asset")` to create a lazy-loaded link to an asset.

```kotlin
val warriorRef = reference<Blueprint>("character/warrior")
val fireballRef = reference<Ability>("ability/fireball")
```

#### Blueprints
Templates for entities, including components and tags.

```kotlin
// script/assets/warrior.udea.kts
blueprint("character/warrior") {
    component(Transform())
    component(Body(type = DynamicBody))
    component(Abilities())
    tag(Tags.Player)
}

// Spawning from blueprint
val entity = reference<Blueprint>("character/warrior").value.newInstance(world) { entity ->
    entity[Transform].position.set(10f, 10f)
}
```

#### Asset Bundles
Group multiple assets in a single file.

```kotlin
// script/assets/items.udea.kts
bundle {
    blueprint("item/sword") { ... }
    blueprint("item/shield") { ... }
}
```

#### Code Generation
Annotate any class with `@CreateDsl` to generate its DSL builder.
```kotlin
@CreateDsl(name = "myDsl")
class MyData(val value: Float)
```
The Gradle plugin will generate:
```kotlin
fun myDsl(value: Float) = MyData(value)
```
