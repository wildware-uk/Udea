package dev.wildware.udea.assets

data class GameConfig(
    val defaultLevel: AssetReference<Level>? = null,
    val defaultCharacter: AssetReference<Blueprint>? = null,
    val backgroundTexture: String? = null,
) : Asset()
