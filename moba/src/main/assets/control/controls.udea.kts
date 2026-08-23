// Migrated from example/src/main/resources/assets/control/controls.udea.kts (issue #93).
//
// The key codes are libGDX's `com.badlogic.gdx.Input.Keys` values, written as literals rather
// than imported. The asset compile classpath is deliberately the asset model and not the whole
// application (`AssetCompiler.scriptClasspath`), so a control script does not drag the renderer
// into the graph that pass 2 has to compile against.
val KeyQ = 45
val KeyW = 51
val KeyA = 29
val KeyS = 47
val KeyD = 32
val KeySpace = 62

control(name = "attack")
control(name = "attack_2")
axis2D(name = "move")

binding(
    name = "attack_binding",
    control = reference("control/attack"),
    input = key(KeySpace),
)

binding(
    name = "attack_2_binding",
    control = reference("control/attack_2"),
    input = key(KeyQ),
)

axis2DBinding(
    name = "move_left",
    axis = reference("control/move"),
    input = key(KeyA),
    direction = vec(-1.0F, 0.0F),
)

axis2DBinding(
    name = "move_right",
    axis = reference("control/move"),
    input = key(KeyD),
    direction = vec(1.0F, 0.0F),
)

axis2DBinding(
    name = "move_up",
    axis = reference("control/move"),
    input = key(KeyW),
    direction = vec(0.0F, 1.0F),
)

axis2DBinding(
    name = "move_down",
    axis = reference("control/move"),
    input = key(KeyS),
    direction = vec(0.0F, -1.0F),
)
