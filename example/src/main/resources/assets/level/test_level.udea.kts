import dev.wildware.udea.example.system.HealthbarSystem
import dev.wildware.udea.example.system.UnitAISystem
import kotlin.random.Random

level(
    systems = {
        add(UnitAISystem::class)
        add(HealthbarSystem::class)
    },

    entities = {
        val spawnDistance = 2F
        fun randomPos() = Vector2(
            Random.nextFloat() * spawnDistance - spawnDistance / 2,
            Random.nextFloat() * spawnDistance - spawnDistance / 2
        )

        repeat(20) {
            entityDefinition(
                blueprint = reference("character/soldier"),
                position = randomPos()
            )

            entityDefinition(
                blueprint = reference("character/orc"),
                position = randomPos()
            )
        }
    }
)
