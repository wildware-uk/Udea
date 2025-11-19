import dev.wildware.udea.assets.blueprint
import dev.wildware.udea.assets.lazy
import dev.wildware.udea.ecs.component.base.transform
import dev.wildware.udea.ecs.component.control.controller

blueprint(
    components = lazy {
        transform()
        controller()
    }
)
