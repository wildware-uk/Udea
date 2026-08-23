// The expressiveness that motivated keeping .udea.kts at all (issue #87): a local helper
// function and a repeat(n) loop, modelled on the real
// example/src/main/resources/assets/level/test_level.udea.kts.
fun spawn(kind: String): Ref = reference("character/$kind")

val entities = mutableListOf<Ref>()

repeat(3) {
    entities.add(spawn("orc"))
}
entities.add(spawn("goblin"))

level(entities = entities)

repeat(2) { index ->
    blueprint(name = "spawner_$index", components = listOf("spawn"))
}
