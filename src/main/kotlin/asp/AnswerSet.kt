package miv.dev.ru.asp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AnswerSet(val atoms: Set<String>) {

    fun contains(atom: String): Boolean = atoms.contains(atom)
    fun any(pred: (String) -> Boolean): Boolean = atoms.any(pred)
    fun filter(pred: (String) -> Boolean): List<String> = atoms.filter(pred)
    fun firstOrNull(pred: (String) -> Boolean): String? = atoms.firstOrNull(pred)

    companion object {
        val EMPTY = AnswerSet(emptySet())

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(output: String): AnswerSet {
            return try {
                val root = json.parseToJsonElement(output).jsonObject
                val witnesses = root["Call"]
                    ?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("Witnesses")
                    ?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("Value")
                    ?.jsonArray
                    ?: return EMPTY
                val atoms = witnesses.map { it.jsonPrimitive.content }.toSet()
                AnswerSet(atoms)
            } catch (e: Exception) {
                EMPTY
            }
        }
    }
}