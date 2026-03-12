package miv.dev.ru.asp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import miv.dev.ru.domain.FieldSignal
import miv.dev.ru.domain.FormSchema
import miv.dev.ru.domain.RuleSet
import org.slf4j.LoggerFactory

class AspRulesEngine(private val clingoPath: String = "clingo") {
    private val log = LoggerFactory.getLogger(AspRulesEngine::class.java)

    suspend fun evaluate(
        schema: FormSchema,
        rules: RuleSet,
        currentValues: Map<String, String>,
        role: String = "user"
    ): List<FieldSignal> = withContext(Dispatchers.IO) {
        val facts = buildFacts(schema, currentValues, role)
        val answerSet = runClingo(facts, rules.lpContent)
        mapToSignals(schema, answerSet)
    }

    private fun buildFacts(
        schema: FormSchema,
        values: Map<String, String>,
        role: String
    ): String = buildString {
        schema.fields.forEach { f ->
            appendLine("field(${f.id}).")
            appendLine("field_type(${f.id}, ${f.type.name.lowercase()}).")
        }
        values.forEach { (k, v) ->
            val escaped = v.replace("\"", "\\\"")
            appendLine("""field_value($k, "$escaped").""")
        }
        schema.fields.forEach { f ->
            f.dataSource?.let { appendLine("data_source(${f.id}, ${it.triggerField}).") }
        }
        appendLine("""user_role("$role").""")
    }

    private suspend fun runClingo(facts: String, rulesLp: String): AnswerSet =
        withContext(Dispatchers.IO) {
            val combined = facts + "\n" + rulesLp
            try {
                val process = ProcessBuilder(clingoPath, "--outf=2", "-")
                    .redirectErrorStream(false)
                    .start()

                process.outputStream.bufferedWriter().use { it.write(combined) }

                val stdout = process.inputStream.bufferedReader().readText()
                // Drain stderr to prevent process blocking
                process.errorStream.bufferedReader().readText()

                val exitCode = process.waitFor()
                // Clingo exit codes: 10 = SAT, 20 = UNSAT, 30 = UNKNOWN
                if (exitCode == 20) {
                    log.warn("Clingo returned UNSAT for rules evaluation")
                    return@withContext AnswerSet.EMPTY
                }
                if (exitCode !in listOf(10, 20, 30)) {
                    log.error("Clingo exited with unexpected code $exitCode")
                    return@withContext AnswerSet.EMPTY
                }
                AnswerSet.parse(stdout)
            } catch (e: Exception) {
                log.error("Clingo subprocess failed: ${e.message}")
                AnswerSet.EMPTY
            }
        }

    private fun mapToSignals(schema: FormSchema, answerSet: AnswerSet): List<FieldSignal> {
        val fieldSignals = schema.fields.map { field ->
            val id = field.id
            val invalidAtom = answerSet.firstOrNull { it.startsWith("invalid($id,") }
            FieldSignal(
                fieldId = id,
                visible = answerSet.contains("visible($id)"),
                required = answerSet.contains("required($id)"),
                readOnly = answerSet.contains("readonly($id)"),
                valid = when {
                    answerSet.contains("valid($id)") -> true
                    invalidAtom != null -> false
                    else -> null
                },
                validationMessage = invalidAtom?.let { extractMessage(it) },
                hints = answerSet.filter { it.startsWith("hint($id,") }.map { extractHintKey(it) },
                fetchOptions = answerSet.contains("fetch_options($id)")
            )
        }
        val submitSignal = FieldSignal(
            fieldId = "__submit__",
            visible = answerSet.contains("submit_allowed"),
            required = false
        )
        return fieldSignals + submitSignal
    }

    // invalid(fieldId,"message") → message
    private fun extractMessage(atom: String): String {
        val start = atom.indexOf('"') + 1
        val end = atom.lastIndexOf('"')
        return if (start in 1 until end) atom.substring(start, end) else ""
    }

    // hint(fieldId,"key") → key
    private fun extractHintKey(atom: String): String {
        val start = atom.indexOf('"') + 1
        val end = atom.lastIndexOf('"')
        return if (start in 1 until end) atom.substring(start, end) else atom
    }
}