import dev.wildware.udea.ecs.component.base.networkable
import dev.wildware.udea.example.component.aIUnit
import dev.wildware.udea.example.component.player
import dev.wildware.udea.example.system.GameUnitSystem
import dev.wildware.udea.example.system.HealthbarSystem
import dev.wildware.udea.example.system.PlayerControlSystem
import dev.wildware.udea.example.system.ProjectileSystem
import dev.wildware.udea.example.system.UnitAISystem
import kotlin.random.Random

level(
    systems = {
        add(ProjectileSystem::class)
        add(UnitAISystem::class)
        add(PlayerControlSystem::class)
        add(GameUnitSystem::class)
        add(HealthbarSystem::class)
    },

    entities = {
        val spawnDistance = 2F
        fun randomPos() = Vector2(
            Random.nextFloat() * spawnDistance - spawnDistance / 2,
            Random.nextFloat() * spawnDistance - spawnDistance / 2
        )

        entityDefinition(
            blueprint = reference("character/soldier"),
            components = lazy {
                networkable(owner = -1)
                player()
            }
        )

        repeat(5) {
            entityDefinition(
                blueprint = reference("character/soldier"),
                position = randomPos(),
                components = lazy {
                    aIUnit()
                }
            )

            entityDefinition(
                blueprint = reference("character/orc"),
                position = randomPos(),
                components = lazy {
                    aIUnit()
                }
            )
        }
    }
)
