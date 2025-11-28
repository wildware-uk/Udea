import dev.wildware.udea.ecs.component.base.networkable
import dev.wildware.udea.ecs.component.render.animationHolder
import dev.wildware.udea.example.component.aIUnit
import dev.wildware.udea.example.component.player
import dev.wildware.udea.example.system.*
import kotlin.random.Random

// TODO ability spec system I GET IT NOW

level(
    systems = {
        add(EffectSystem::class)
        add(ProjectileSystem::class)
        add(UnitAISystem::class)
        add(PlayerControlSystem::class)
        add(GameUnitSystem::class)
        add(HealthbarSystem::class)
    },

    entities = {
        val spawnDistance = 3F
        fun randomPos() = Vector2(
            Random.nextFloat() * spawnDistance - spawnDistance / 2,
            Random.nextFloat() * spawnDistance - spawnDistance / 2
        )

        entityDefinition(
            blueprint = reference("character/priest"),
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
