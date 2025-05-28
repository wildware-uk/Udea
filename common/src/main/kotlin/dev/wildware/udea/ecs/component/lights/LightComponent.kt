package dev.wildware.udea.ecs.component.lights

import box2dLight.Light

sealed interface LightComponent {
    val light: Light
}
