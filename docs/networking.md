### 🌐 Networking & Synchronization
Udea simplifies multiplayer development with automatic synchronization of components and properties.

#### 🔄 Synchronization
Annotate your classes and properties to mark them for networking.

```kotlin
@UdeaNetworked
@Serializable
data class MySyncComp(
    @UdeaSync
    var health: Float = 100f
) : Component<MySyncComp> {
    override fun type() = MySyncComp
    
    companion object : UdeaComponentType<MySyncComp>(
        networkComponent = configureNetwork(
            syncStrategy = Update, // Sync only on updates
            syncTick = 10,         // Sync every 10 ticks (throttling)
            networkAuthority = Client // Client (owner) can update this
        )
    )
}
```

#### ⚖️ Authoritative Model
The server is the source of truth. It processes `EntityUpdate` packets and multicasts state to all clients.

*   **`Networkable` Component:** Entities with this component are tracked by the server.
*   **Owner:** The client ID that owns the entity (for client-side updates).

```kotlin
// Server spawns a networked entity
entity.configure {
    it += Networkable(owner = 1) // owned by client 1
}
```

#### 🔗 Engine Setup
Choose your networking role in the `GameScreen`.

```kotlin
// Host (Server + Player)
val worldSource = WorldSource.Host(tcpPort = 28855, udpPort = 28856)

// Client (Connect to Server)
val worldSource = WorldSource.Connect(ip = "127.0.0.1", tcpPort = 28855, udpPort = 28856)
```
