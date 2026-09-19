import com.badlogic.gdx.Input

bundle {
    control(name = "attack")
    control(name = "attack_2")
    axis2D(name = "move")

    binding(
        name = "attack_binding",
        control = reference("control/attack"),
        input = key(Input.Keys.SPACE)
    )

    binding(
        name = "attack_2_binding",
        control = reference("control/attack_2"),
        input = key(Input.Keys.Q)
    )

    axis2DBinding(
        name = "move_left",
        axis = reference("control/move"),
        input = key(Input.Keys.A),
        direction = Vector2(-1.0F, 0.0F)
    )

    axis2DBinding(
        name = "move_right",
        axis = reference("control/move"),
        input = key(Input.Keys.D),
        direction = Vector2(1.0F, 0.0F)
    )

    axis2DBinding(
        name = "move_up",
        axis = reference("control/move"),
        input = key(Input.Keys.W),
        direction = Vector2(0.0F, 1.0F)
    )

    axis2DBinding(
        name = "move_down",
        axis = reference("control/move"),
        input = key(Input.Keys.S),
        direction = Vector2(0.0F, -1.0F)
    )
}
