import dev.wildware.udea.ecs.component.base.networkable
import dev.wildware.udea.example.component.player

blueprint(
    parent = reference("character/soldier"),
    components = lazy {
        player()
        networkable()
    }
)
