import dev.wildware.udea.ecs.component.animation.animations
import dev.wildware.udea.ecs.component.render.spriteRenderer

bundle {
    blueprint(
        name = "effect",
        components = lazy {
            spriteRenderer(
                order = 10,
                offset = Vector2(0.0F, -0.1F)
            )
            animations()
        }
    )
}
