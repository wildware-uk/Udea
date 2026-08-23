val entities = mutableListOf<Ref>()

repeat(3) { entities.add(reference("blueprint/minion")) }
entities.add(reference("blueprint/player"))

level(name = "arena", entities = entities)
