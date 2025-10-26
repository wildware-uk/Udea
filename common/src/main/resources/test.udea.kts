import dev.wildware.udea.assets.blueprint
import dev.wildware.udea.assets.dsl.list
import dev.wildware.udea.ecs.component.base.transform
import dev.wildware.udea.ecs.component.control.controller

blueprint(
    components = {
        list {
            transform()
            controller()
        }
    }
)
