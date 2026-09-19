// The expressiveness that motivated keeping .udea.kts at all (issue #87): a local helper
// function, called once per entity. It used to be called through a repeat(n) loop, modelled on
// example-assets/level/test_level.udea.kts; loops are banned in assets since
// issue #192, so each call is written out.
fun spawn(kind: String): Ref = reference("character/$kind")

val entities = mutableListOf<Ref>()

entities.add(spawn("orc"))
entities.add(spawn("orc"))
entities.add(spawn("orc"))
entities.add(spawn("goblin"))

level(entities = entities)

blueprint(name = "spawner_0", components = listOf("spawn"))
blueprint(name = "spawner_1", components = listOf("spawn"))
