blueprint(name = "player", components = listOf("networkable", "team", "gameUnit"))

blueprint(name = "minion", parent = reference("blueprint/player"), components = listOf("ai"))
