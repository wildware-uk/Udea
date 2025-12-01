import dev.wildware.udea.ecs.component.base.networkable
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
        val spawnDistance = 4F
        fun randomPos() = Vector2(
            Random.nextFloat() * spawnDistance - spawnDistance / 2,
            Random.nextFloat() * spawnDistance - spawnDistance / 2
        )

        entityDefinition(
            blueprint = reference("character/orc"),
            components = lazy {
                networkable(owner = -1)
                player()
            },
            position = randomPos().sub(5F, 0F)
        )

//        entityDefinition(
//            blueprint = reference("character/priest"),
//            components = lazy {
//                aIUnit()
//            },
//            position = randomPos()
//        )

//        repeat(5) {
//            entityDefinition(
//                blueprint = reference("character/orc"),
//                components = lazy {
//                    aIUnit()
//                },
//                position = randomPos().sub(5F, 0F)
//            )
//        }
//
//        repeat(10) {
            entityDefinition(
                blueprint = reference("character/skeleton"),
                components = lazy {
                    aIUnit()
                },
                position = randomPos().sub(-10F, 0F)
            )
//        }
//
//        repeat(10) {
//            entityDefinition(
//                blueprint = reference("character/soldier"),
//                components = lazy {
//                    aIUnit()
//                },
//                position = randomPos().sub(0F, 5F)
//            )
//        }
    }
)
