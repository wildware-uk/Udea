val entities = mutableListOf<Ref>()

entities.add(reference("blueprint/minion"))
entities.add(reference("blueprint/minion"))
entities.add(reference("blueprint/minion"))
entities.add(reference("blueprint/player"))

level(name = "arena", entities = entities)
