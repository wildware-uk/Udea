// Migrated from example/src/main/resources/assets/blueprint/player.udea.kts (issue #93).

blueprint(
    parent = reference("character/orc_elite"),
    components = {
        component("dev.wildware.udea.example.component.Player")
        component("dev.wildware.udea.ecs.component.base.Networkable")
    },
)
