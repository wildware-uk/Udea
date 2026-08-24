character(
    name = "goblin",
    size = 0.2f,
    health = 120f,
    animationMap = mapOf("idle" to reference("character/goblin_idle")),
)

spriteAnimation(name = "goblin_idle", sheet = reference("character/goblin_idle_sheet"))

spriteSheet(name = "goblin_idle_sheet", spritePath = "/sprites/goblin/idle.png", columns = 4, scale = 0.02f)

// `parent` takes a `SpawnRecipe`, so a blueprint may inherit a character - which is how the old
// game spelled "the player *is* the elite orc".
blueprint(name = "goblin_spawn", parent = reference("character/goblin"), components = listOf("ai", "team"))

// The generic escape, and the corpus's one kind with no runtime type at all. It is what keeps
// `AssetKind.Unpublishable` exercised now that `character` is published: a game declares its own
// kinds, and this is what one looks like on the way through the pipeline.
asset("particle", "goblin_dust", "lifetime" to 1.5f)
