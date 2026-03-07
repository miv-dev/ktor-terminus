package miv.dev.ru.forms

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import miv.dev.ru.domain.*
import miv.dev.ru.terminus.SchemaMapper
import miv.dev.ru.terminus.TerminusClient
import org.slf4j.LoggerFactory

@Serializable
data class CommitEntry(
    val id: String,
    val message: String,
    val timestamp: String,
    val author: String
)

class FormRepository(private val client: TerminusClient) {
    private val log = LoggerFactory.getLogger(FormRepository::class.java)

    // ── Schema ───────────────────────────────────────────────────────────────

    suspend fun save(schema: FormSchema) {
        val docs = mutableListOf<JsonObject>()
        docs.add(SchemaMapper.formSchemaToDoc(schema))
        schema.fields.forEach { docs.add(SchemaMapper.formFieldToDoc(schema.id, it)) }
        client.insertDocumentsWithMessage(docs, "Create form ${schema.id} with ${schema.fields.size} fields")
        log.info("Saved form schema: ${schema.id}")
    }

    suspend fun update(schema: FormSchema, message: String? = null) {
        val docs = mutableListOf<JsonObject>()
        docs.add(SchemaMapper.formSchemaToDoc(schema))
        schema.fields.forEach { docs.add(SchemaMapper.formFieldToDoc(schema.id, it)) }
        client.replaceDocuments(docs, message ?: "Update form ${schema.id}")
        log.info("Updated form schema: ${schema.id}")
    }

    suspend fun findById(id: String): FormSchema? {
        return try {
            val schemaDoc = client.getDocument("FormSchema/$id") ?: return null
            val fieldRefs = schemaDoc["fields"]?.jsonArray ?: return null
            val fields = fieldRefs.mapNotNull { ref ->
                val docId = when {
                    ref is JsonPrimitive -> ref.content
                    ref is JsonObject -> ref["@id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    else -> return@mapNotNull null
                }
                try {
                    val doc = client.getDocument(docId) ?: return@mapNotNull null
                    SchemaMapper.docToFormField(doc)
                } catch (e: Exception) {
                    log.warn("Failed to load field $docId: ${e.message}")
                    null
                }
            }
            SchemaMapper.docToFormSchema(schemaDoc, fields)
        } catch (e: Exception) {
            log.error("Failed to load form $id: ${e.message}")
            null
        }
    }

    // ── RuleSet ──────────────────────────────────────────────────────────────

    suspend fun saveRuleSet(ruleSet: RuleSet) {
        client.insertDocumentsWithMessage(
            listOf(SchemaMapper.ruleSetToDoc(ruleSet)),
            "Create ruleset for ${ruleSet.formId}"
        )
        log.info("Saved ruleset for ${ruleSet.formId}")
    }

    suspend fun updateRuleSet(ruleSet: RuleSet, message: String? = null) {
        client.replaceDocuments(
            listOf(SchemaMapper.ruleSetToDoc(ruleSet)),
            message ?: "Update ruleset for ${ruleSet.formId}"
        )
        log.info("Updated ruleset for ${ruleSet.formId}")
    }

    suspend fun getRuleSet(formId: String): RuleSet? {
        return try {
            val doc = client.getDocument("RuleSet/$formId") ?: return null
            SchemaMapper.docToRuleSet(doc)
        } catch (e: Exception) {
            log.error("Failed to load ruleset for $formId: ${e.message}")
            null
        }
    }

    // ── History ──────────────────────────────────────────────────────────────

    suspend fun getHistory(): List<CommitEntry> {
        return try {
            client.getCommitLog().map { entry ->
                val obj = entry.jsonObject
                CommitEntry(
                    id = obj["identifier"]?.jsonPrimitive?.content ?: obj["@id"]?.jsonPrimitive?.content ?: "",
                    message = obj["message"]?.jsonPrimitive?.content ?: "",
                    timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                    author = obj["author"]?.jsonPrimitive?.content ?: ""
                )
            }
        } catch (e: Exception) {
            log.error("Failed to get history: ${e.message}")
            emptyList()
        }
    }

    // ── Diff ─────────────────────────────────────────────────────────────────

    suspend fun getDiff(formId: String, fromCommit: String, toCommit: String): JsonObject {
        return try {
            val fromFields = fieldsAtCommit(fromCommit)
            val toFields = fieldsAtCommit(toCommit)
            val fromMap = fromFields.associateBy { it.id }
            val toMap = toFields.associateBy { it.id }

            val added = toMap.keys - fromMap.keys
            val removed = fromMap.keys - toMap.keys
            val modified = fromMap.keys.intersect(toMap.keys)
                .filter { fromMap[it] != toMap[it] }

            val fromRules = ruleSetAtCommit(fromCommit, formId)?.lpContent ?: ""
            val toRules = ruleSetAtCommit(toCommit, formId)?.lpContent ?: ""

            buildJsonObject {
                put("from", fromCommit.take(10))
                put("to", toCommit.take(10))
                put("schema", buildJsonObject {
                    put("added", buildJsonArray { added.forEach { add(it) } })
                    put("removed", buildJsonArray { removed.forEach { add(it) } })
                    put("modified", buildJsonArray {
                        modified.forEach { id ->
                            add(buildJsonObject {
                                put("field", id)
                                put("before", fromMap[id]!!.label)
                                put("after", toMap[id]!!.label)
                            })
                        }
                    })
                    put("unchanged", fromMap.keys.intersect(toMap.keys).size - modified.size)
                })
                put("rules", buildJsonObject {
                    put("changed", fromRules != toRules)
                    if (fromRules != toRules) {
                        put("before", fromRules)
                        put("after", toRules)
                    }
                })
            }
        } catch (e: Exception) {
            log.error("Failed to compute diff: ${e.message}")
            buildJsonObject { put("error", e.message ?: "unknown") }
        }
    }

    private suspend fun fieldsAtCommit(commitId: String): List<FormField> =
        client.queryDocumentsAtCommit(commitId, "FormField").mapNotNull { el ->
            try { SchemaMapper.docToFormField(el.jsonObject) } catch (e: Exception) { null }
        }

    private suspend fun ruleSetAtCommit(commitId: String, formId: String): RuleSet? {
        val doc = client.getDocumentAtCommit(commitId, "RuleSet/$formId") ?: return null
        return try { SchemaMapper.docToRuleSet(doc) } catch (e: Exception) { null }
    }

    // ── Checkout ─────────────────────────────────────────────────────────────

    suspend fun checkout(formId: String, commitId: String): FormSchema? {
        log.info("Checking out commit $commitId for form $formId")
        return try {
            val schemaDoc = client.getDocumentAtCommit(commitId, "FormSchema/$formId")
                ?: return null
            val fieldDocs = client.queryDocumentsAtCommit(commitId, "FormField")
            val fields = fieldDocs.mapNotNull { el ->
                try { SchemaMapper.docToFormField(el.jsonObject) } catch (e: Exception) {
                    log.warn("Skipping field at commit $commitId: ${e.message}")
                    null
                }
            }
            val schema = SchemaMapper.docToFormSchema(schemaDoc, fields)

            // Restore RuleSet at that commit
            val rules = ruleSetAtCommit(commitId, formId)
            if (rules != null) {
                updateRuleSet(rules, "Checkout: restore rules to commit ${commitId.take(8)}")
            }

            update(schema, "Checkout: rollback to commit ${commitId.take(8)}")
            log.info("Rolled back form $formId to commit ${commitId.take(8)} (${fields.size} fields)")
            schema
        } catch (e: Exception) {
            log.error("Checkout failed for commit $commitId: ${e.message}")
            null
        }
    }

    suspend fun generateUpdateMessage(before: FormSchema, after: FormSchema): String {
        val beforeIds = before.fields.map { it.id }.toSet()
        val afterIds = after.fields.map { it.id }.toSet()
        val added = afterIds - beforeIds
        val removed = beforeIds - afterIds
        val modified = afterIds.intersect(beforeIds).count { id ->
            before.fields.first { it.id == id } != after.fields.first { it.id == id }
        }
        return buildString {
            append("Update form ${after.id}:")
            if (added.isNotEmpty()) append(" added ${added.size} field(s) (${added.joinToString()})")
            if (removed.isNotEmpty()) append(", removed ${removed.size} field(s) (${removed.joinToString()})")
            if (modified > 0) append(", modified $modified field(s)")
        }
    }
}
