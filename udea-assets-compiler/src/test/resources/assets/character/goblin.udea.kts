character(
    name = "goblin",
    size = 0.2f,
    health = 120f,
    animations = listOf(reference("character/goblin_idle")),
)

spriteSheet(name = "goblin_idle", spritePath = "/sprites/goblin/idle.png", columns = 4, scale = 0.02f)

blueprint(name = "goblin_spawn", parent = reference("character/goblin"), components = listOf("ai", "team"))
