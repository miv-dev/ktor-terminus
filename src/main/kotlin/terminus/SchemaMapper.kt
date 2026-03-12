package miv.dev.ru.terminus

import kotlinx.serialization.json.*
import miv.dev.ru.domain.DataSource
import miv.dev.ru.domain.FieldType
import miv.dev.ru.domain.FormField
import miv.dev.ru.domain.FormSchema
import miv.dev.ru.domain.RuleSet
import miv.dev.ru.domain.SubmitAction

object SchemaMapper {

    // ── Domain → JSON-LD ────────────────────────────────────────────────────

    fun formSchemaToDoc(schema: FormSchema): JsonObject = buildJsonObject {
        put("@type", "FormSchema")
        put("@id", "FormSchema/${schema.id}")
        put("formId", schema.id)
        put("name", schema.name)
        put("fields", buildJsonArray {
            schema.fields.forEach { add("FormField/${schema.id}_${it.id}") }
        })
        schema.submitAction?.let { sa ->
            put("submitAction", buildJsonObject {
                put("@type", "SubmitAction")
                put("url", sa.url)
                put("method", sa.method)
                put("headers", buildJsonObject { sa.headers.forEach { (k, v) -> put(k, v) } }.toString())
            })
        }
    }

    fun formFieldToDoc(schemaId: String, field: FormField): JsonObject = buildJsonObject {
        put("@type", "FormField")
        put("@id", "FormField/${schemaId}_${field.id}")
        put("fieldId", field.id)
        put("label", field.label)
        put("fieldType", field.type.name)
        put("placeholder", field.placeholder)
        put("options", buildJsonArray { field.options.forEach { add(it) } })
        put("form", "FormSchema/$schemaId")
        field.dataSource?.let { ds ->
            put("dataSource", buildJsonObject {
                put("@type", "DataSource")
                put("url", ds.url)
                put("method", ds.method)
                put("bodyParam", ds.bodyParam)
                put("queryParam", ds.queryParam)
                put("triggerField", ds.triggerField)
                put("responsePath", ds.responsePath)
            })
        }
    }

    fun ruleSetToDoc(ruleSet: RuleSet): JsonObject = buildJsonObject {
        put("@type", "RuleSet")
        put("@id", "RuleSet/${ruleSet.formId}")
        put("formId", ruleSet.formId)
        put("lpContent", ruleSet.lpContent)
    }

    // ── JSON-LD → Domain ────────────────────────────────────────────────────

    fun docToFormSchema(doc: JsonObject, fields: List<FormField>): FormSchema = FormSchema(
        id = doc["formId"]!!.jsonPrimitive.content,
        name = doc["name"]!!.jsonPrimitive.content,
        fields = fields,
        submitAction = doc["submitAction"]?.takeIf { it is JsonObject }?.jsonObject?.let { sa ->
            SubmitAction(
                url = sa["url"]!!.jsonPrimitive.content,
                method = sa["method"]?.jsonPrimitive?.content ?: "POST",
                headers = sa["headers"]?.jsonPrimitive?.content
                    ?.let { Json.parseToJsonElement(it).jsonObject.mapValues { e -> e.value.jsonPrimitive.content } }
                    ?: emptyMap()
            )
        }
    )

    fun docToFormField(doc: JsonObject): FormField = FormField(
        id = doc["fieldId"]!!.jsonPrimitive.content,
        label = doc["label"]!!.jsonPrimitive.content,
        type = FieldType.valueOf(doc["fieldType"]!!.jsonPrimitive.content),
        placeholder = doc["placeholder"]?.jsonPrimitive?.content ?: "",
        options = doc["options"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        dataSource = doc["dataSource"]?.takeIf { it is JsonObject }?.jsonObject?.let { ds ->
            DataSource(
                url = ds["url"]!!.jsonPrimitive.content,
                method = ds["method"]?.jsonPrimitive?.content ?: "POST",
                bodyParam = ds["bodyParam"]?.jsonPrimitive?.content ?: "",
                queryParam = ds["queryParam"]?.jsonPrimitive?.content ?: "",
                triggerField = ds["triggerField"]!!.jsonPrimitive.content,
                responsePath = ds["responsePath"]?.jsonPrimitive?.content ?: "data"
            )
        }
    )

    fun docToRuleSet(doc: JsonObject): RuleSet = RuleSet(
        formId = doc["formId"]!!.jsonPrimitive.content,
        lpContent = doc["lpContent"]!!.jsonPrimitive.content
    )

    // ── OWL/Schema definition for TerminusDB ────────────────────────────────

    fun buildTerminusSchema(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "FormSchema")
            put("@documentation", buildJsonObject { put("@comment", "A form schema definition") })
            put("formId", "xsd:string")
            put("name", "xsd:string")
            put("fields", buildJsonObject {
                put("@type", "Set")
                put("@class", "FormField")
            })
            put("submitAction", buildJsonObject {
                put("@type", "Optional")
                put("@class", "SubmitAction")
            })
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "SubmitAction")
            put("@subdocument", buildJsonArray {})
            put("@key", buildJsonObject { put("@type", "Random") })
            put("url", "xsd:string")
            put("method", "xsd:string")
            put("headers", "xsd:string")
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "FormField")
            put("fieldId", "xsd:string")
            put("label", "xsd:string")
            put("fieldType", "xsd:string")
            put("placeholder", "xsd:string")
            put("options", buildJsonObject {
                put("@type", "List")
                put("@class", "xsd:string")
            })
            put("form", "FormSchema")
            put("dataSource", buildJsonObject {
                put("@type", "Optional")
                put("@class", "DataSource")
            })
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "DataSource")
            put("@subdocument", buildJsonArray {})
            put("@key", buildJsonObject { put("@type", "Random") })
            put("url", "xsd:string")
            put("method", "xsd:string")
            put("bodyParam", "xsd:string")
            put("queryParam", "xsd:string")
            put("triggerField", "xsd:string")
            put("responsePath", "xsd:string")
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "RuleSet")
            put("@documentation", buildJsonObject { put("@comment", "ASP .lp rules text for a form") })
            put("formId", "xsd:string")
            put("lpContent", "xsd:string")
        })
    }
}
