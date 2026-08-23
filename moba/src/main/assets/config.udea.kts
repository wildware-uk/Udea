// Migrated from example/src/main/resources/assets/config.udea.kts (issue #93).

gameConfig(
    defaultCharacter = reference("blueprint/player"),
    defaultLevel = reference("level/test_level"),
    physics = physics(gravity = vec(0.0F, 0.0F)),
)
