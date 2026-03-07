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

    suspend fun save(schema: FormSchema) {
        val docs = mutableListOf<JsonObject>()
        docs.add(SchemaMapper.formSchemaToDoc(schema))
        schema.fields.forEach { field ->
            docs.add(SchemaMapper.formFieldToDoc(schema.id, field))
        }
        val message = "Create form ${schema.id} with ${schema.fields.size} fields"
        client.insertDocumentsWithMessage(docs, message)
        log.info("Saved form schema: ${schema.id}")
    }

    suspend fun update(schema: FormSchema, message: String? = null) {
        val docs = mutableListOf<JsonObject>()
        docs.add(SchemaMapper.formSchemaToDoc(schema))
        schema.fields.forEach { field ->
            docs.add(SchemaMapper.formFieldToDoc(schema.id, field))
        }
        val commitMsg = message ?: "Update form ${schema.id}"
        client.replaceDocuments(docs, commitMsg)
        log.info("Updated form schema: ${schema.id}")
    }

    suspend fun findById(id: String): FormSchema? {
        return try {
            val schemaDoc = client.getDocument("FormSchema/$id") ?: return null
            val fieldDocs = loadFieldDocs(id, schemaDoc)
            SchemaMapper.docToFormSchema(schemaDoc, fieldDocs)
        } catch (e: Exception) {
            log.error("Failed to load form $id: ${e.message}")
            null
        }
    }

    private suspend fun loadFieldDocs(schemaId: String, schemaDoc: JsonObject): List<FormField> {
        val fieldRefs = schemaDoc["fields"]?.jsonArray ?: return emptyList()
        return fieldRefs.mapNotNull { ref ->
            val fieldDocId = when {
                ref is JsonPrimitive -> ref.content
                ref is JsonObject -> ref["@id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                else -> return@mapNotNull null
            }
            try {
                val doc = client.getDocument(fieldDocId) ?: return@mapNotNull null
                SchemaMapper.docToFormField(doc)
            } catch (e: Exception) {
                log.warn("Failed to load field $fieldDocId: ${e.message}")
                null
            }
        }
    }

    suspend fun getHistory(): List<CommitEntry> {
        return try {
            val log = client.getCommitLog()
            log.map { entry ->
                val obj = entry.jsonObject
                CommitEntry(
                    id = obj["identifier"]?.jsonPrimitive?.content ?: obj["@id"]?.jsonPrimitive?.content ?: "",
                    message = obj["message"]?.jsonPrimitive?.content ?: "",
                    timestamp = obj["timestamp"]?.jsonPrimitive?.content ?: "",
                    author = obj["author"]?.jsonPrimitive?.content ?: ""
                )
            }
        } catch (e: Exception) {
            this.log.error("Failed to get history: ${e.message}")
            emptyList()
        }
    }

    suspend fun getDiff(fromCommit: String, toCommit: String): JsonObject {
        return try {
            val fromFields = fieldsAtCommit(fromCommit)
            val toFields = fieldsAtCommit(toCommit)

            val fromMap = fromFields.associateBy { it.id }
            val toMap = toFields.associateBy { it.id }

            val added = toMap.keys - fromMap.keys
            val removed = fromMap.keys - toMap.keys
            val modified = fromMap.keys.intersect(toMap.keys)
                .filter { fromMap[it] != toMap[it] }

            buildJsonObject {
                put("from", fromCommit.take(10))
                put("to", toCommit.take(10))
                put("added", buildJsonArray { added.forEach { add(it) } })
                put("removed", buildJsonArray { removed.forEach { add(it) } })
                put("modified", buildJsonArray {
                    modified.forEach { id ->
                        add(buildJsonObject {
                            put("field", id)
                            put("before", fromMap[id]!!.label)
                            put("after", toMap[id]!!.label)
                            put("visibilityChanged",
                                fromMap[id]!!.visibility != toMap[id]!!.visibility)
                            put("validationChanged",
                                fromMap[id]!!.validation != toMap[id]!!.validation)
                        })
                    }
                })
                put("unchanged", fromMap.keys.intersect(toMap.keys).size - modified.size)
            }
        } catch (e: Exception) {
            log.error("Failed to compute diff: ${e.message}")
            buildJsonObject { put("error", e.message ?: "unknown") }
        }
    }

    private suspend fun fieldsAtCommit(commitId: String): List<FormField> {
        val fieldDocs = client.queryDocumentsAtCommit(commitId, "FormField")
        return fieldDocs.mapNotNull { el ->
            try { SchemaMapper.docToFormField(el.jsonObject) } catch (e: Exception) { null }
        }
    }

    suspend fun checkout(formId: String, commitId: String): FormSchema? {
        log.info("Checking out commit $commitId for form $formId")
        return try {
            // Read schema document at the target commit
            val schemaDoc = client.getDocumentAtCommit(commitId, "FormSchema/$formId")
                ?: return null

            // Read all FormField documents at that commit
            val fieldDocs = client.queryDocumentsAtCommit(commitId, "FormField")
            val fields = fieldDocs.mapNotNull { el ->
                try { SchemaMapper.docToFormField(el.jsonObject) } catch (e: Exception) {
                    log.warn("Skipping field at commit $commitId: ${e.message}")
                    null
                }
            }

            val schema = SchemaMapper.docToFormSchema(schemaDoc, fields)

            // Rewrite as current HEAD so future reads reflect the rolled-back state
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
