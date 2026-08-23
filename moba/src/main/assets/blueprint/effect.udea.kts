// Migrated from example/src/main/resources/assets/blueprint/effect.udea.kts (issue #93).

blueprint(
    name = "effect",
    components = {
        component(
            "dev.wildware.udea.ecs.component.render.SpriteRenderer",
            "order" to 10,
            "offset" to vec(0.0F, -0.1F),
        )
        component("dev.wildware.udea.ecs.component.animation.Animations")
    },
)
