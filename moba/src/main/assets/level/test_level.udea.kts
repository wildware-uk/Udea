// Migrated from example/src/main/resources/assets/level/test_level.udea.kts (issue #93).
//
// The source spawned every entity at `kotlin.random.Random.nextFloat()` positions, so two builds
// of identical sources produced different packs. `DeterminismValidator` bans the name outright
// (UDEA0034) and its advice is "declare the value as a literal", which is what this is: a fixed
// lattice with the same spread and the same group offsets the random scatter had. Seeding the
// generator would not have been enough — a seeded `Random` is still `Random` by name, and the
// number it produces is still not visible in the file an agent is editing.

val spawnDistance = 4F

fun spawnAt(index: Int, offsetX: Float, offsetY: Float): Map<String, Any?> = vec(
    (index * 3 % 7) * (spawnDistance / 7F) - spawnDistance / 2F + offsetX,
    (index * 5 % 7) * (spawnDistance / 7F) - spawnDistance / 2F + offsetY,
)

level(
    systems = listOf(
        "dev.wildware.udea.example.system.EffectSystem",
        "dev.wildware.udea.example.system.ProjectileSystem",
        "dev.wildware.udea.example.system.UnitAISystem",
        "dev.wildware.udea.example.system.PlayerControlSystem",
        "dev.wildware.udea.example.system.GameUnitSystem",
        "dev.wildware.udea.example.system.HealthbarSystem",
    ),
    entities = {
        entity(
            name = "player",
            blueprint = reference("character/soldier"),
            position = spawnAt(0, -5F, 0F),
            components = {
                component("dev.wildware.udea.ecs.component.base.Networkable", "owner" to -1)
                component("dev.wildware.udea.example.component.Player")
            },
        )

        entity(
            name = "priest",
            blueprint = reference("character/priest"),
            position = spawnAt(1, 0F, 0F),
            components = {
                component("dev.wildware.udea.example.component.AIUnit")
            },
        )

        repeat(5) {
            entity(
                name = "orc_$it",
                blueprint = reference("character/orc"),
                position = spawnAt(it, -5F, 0F),
                components = {
                    component("dev.wildware.udea.example.component.AIUnit")
                },
            )
        }

        repeat(10) {
            entity(
                name = "skeleton_$it",
                blueprint = reference("character/skeleton"),
                position = spawnAt(it, 10F, 0F),
                components = {
                    component("dev.wildware.udea.example.component.AIUnit")
                },
            )
        }

        repeat(10) {
            entity(
                name = "soldier_$it",
                blueprint = reference("character/soldier"),
                position = spawnAt(it, 0F, -5F),
                components = {
                    component("dev.wildware.udea.example.component.AIUnit")
                },
            )
        }
    },
)
