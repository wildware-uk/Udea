// Migrated from example/src/main/resources/assets/blueprint/arrow.udea.kts (issue #93).
//
// The source carried `// TODO you have broke ListBuilder<AssetReference>`: the on-hit effects
// were a builder over a type the builder could not hold. They are plain records here, and the
// `reference(...)` inside each one is found by the validator through the nested map exactly as
// a top-level field would be.

blueprint(
    components = {
        component("dev.wildware.udea.ecs.component.physics.Body", "type" to "KinematicBody")

        component(
            "dev.wildware.udea.ecs.component.physics.Box",
            "width" to 0.2F,
            "height" to 0.1F,
            "isSensor" to true,
        )

        component(
            "dev.wildware.udea.example.component.Projectile",
            "onHitEffects" to listOf(
                mapOf(
                    "effect" to reference("ability/damage"),
                    "setByCaller" to mapOf("Data.Damage" to -10F),
                    "tags" to listOf("Damage.Physical"),
                ),
                mapOf(
                    "effect" to reference("ability/knockback"),
                    "setByCaller" to mapOf("Data.Knockback" to 0.2F),
                ),
                mapOf(
                    "effect" to reference("ability/stun"),
                    "setByCaller" to mapOf("Data.Duration" to 0.2F),
                ),
            ),
        )

        component("dev.wildware.udea.example.component.Team")

        component(
            "dev.wildware.udea.ecs.component.render.SpriteRenderer",
            "texture" to resource("sprites/arrow/arrow.png"),
            "scale" to 0.1F,
        )
    },
)
