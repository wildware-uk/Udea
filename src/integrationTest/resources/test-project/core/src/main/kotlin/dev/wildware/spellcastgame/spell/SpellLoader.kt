package dev.wildware.spellcastgame.spell

import dev.wildware.network.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.io.File

object SpellLoader {
    val gameDirectory = "runecraft"
    val gameSaveDirectory = getSaveDirectory()

    fun loadAllSpells(): List<Spell> {
        val spells = mutableListOf<Spell>()
        val spellFiles = gameSaveDirectory.listFiles { file -> file.extension == "rcs" } ?: return spells

        for (file in spellFiles) {
            val spell = cbor.decodeFromByteArray<Spell>(file.readBytes())
            spells.add(spell)
        }

        return spells
    }

    @OptIn(ExperimentalStdlibApi::class, ExperimentalSerializationApi::class)
    fun saveSpell(spell: Spell) {
        val fileName = "${spell.hashCode().toHexString()}.rcs"
        val file = File(gameSaveDirectory, fileName)
        file.writeBytes(cbor.encodeToByteArray(spell))
        println("Spell saved to ${file.absoluteFile}")
    }

    fun getSaveDirectory(): File {
        val userHome = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val saveDir = when {
            os.contains("windows") -> File("$userHome\\AppData\\Local\\$gameDirectory")
            os.contains("mac os x") -> File("$userHome/Library/Application Support/$gameDirectory")
            else -> File("$userHome/.$gameDirectory") // Default for Linux/Unix
        }
        if (!saveDir.exists()) {
            saveDir.mkdirs()
        }
        return saveDir
    }
}
