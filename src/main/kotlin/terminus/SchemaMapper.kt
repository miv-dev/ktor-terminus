package miv.dev.ru.terminus

import kotlinx.serialization.json.*
import miv.dev.ru.domain.*

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
        fields = fields
    )

    fun docToFormField(doc: JsonObject): FormField = FormField(
        id = doc["fieldId"]!!.jsonPrimitive.content,
        label = doc["label"]!!.jsonPrimitive.content,
        type = FieldType.valueOf(doc["fieldType"]!!.jsonPrimitive.content),
        placeholder = doc["placeholder"]?.jsonPrimitive?.content ?: "",
        options = doc["options"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
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
