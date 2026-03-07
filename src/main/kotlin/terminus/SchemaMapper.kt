package miv.dev.ru.terminus

import kotlinx.serialization.json.*
import miv.dev.ru.domain.*

/**
 * Marshals FormSchema / FormField domain objects to/from TerminusDB JSON-LD documents.
 */
object SchemaMapper {

    // ── Domain → JSON-LD ────────────────────────────────────────────────────

    fun formSchemaToDoc(schema: FormSchema): JsonObject = buildJsonObject {
        put("@type", "FormSchema")
        put("@id", "FormSchema/${schema.id}")
        put("formId", schema.id)
        put("name", schema.name)
        put("description", schema.description)
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
        put("form", "FormSchema/$schemaId")
        put("visibilityRule", visibilityToDoc(field.visibility))
        put("validationRules", buildJsonArray {
            field.validation.forEach { add(validationToDoc(it)) }
        })
    }

    private fun visibilityToDoc(rule: VisibilityRule): JsonObject = when (rule) {
        is VisibilityRule.Always -> buildJsonObject { put("@type", "AlwaysVisible") }
        is VisibilityRule.WhenEquals -> buildJsonObject {
            put("@type", "WhenEquals")
            put("fieldRef", rule.fieldId)
            put("matchValue", rule.value)
        }
        is VisibilityRule.WhenRole -> buildJsonObject {
            put("@type", "WhenRole")
            put("role", rule.role)
        }
        is VisibilityRule.WhenAny -> buildJsonObject {
            put("@type", "WhenAny")
            put("rules", buildJsonArray { rule.rules.forEach { add(visibilityToDoc(it)) } })
        }
    }

    private fun validationToDoc(rule: ValidationRule): JsonObject = when (rule) {
        is ValidationRule.Required -> buildJsonObject { put("@type", "RequiredRule") }
        is ValidationRule.MinLength -> buildJsonObject {
            put("@type", "MinLengthRule")
            put("minLength", rule.min)
        }
        is ValidationRule.MaxLength -> buildJsonObject {
            put("@type", "MaxLengthRule")
            put("maxLength", rule.max)
        }
        is ValidationRule.MatchesRegex -> buildJsonObject {
            put("@type", "RegexRule")
            put("pattern", rule.pattern)
        }
        is ValidationRule.RequiredWhen -> buildJsonObject {
            put("@type", "RequiredWhenRule")
            put("condition", visibilityToDoc(rule.condition))
        }
    }

    // ── JSON-LD → Domain ────────────────────────────────────────────────────

    fun docToFormSchema(doc: JsonObject, fields: List<FormField>): FormSchema = FormSchema(
        id = doc["formId"]!!.jsonPrimitive.content,
        name = doc["name"]!!.jsonPrimitive.content,
        description = doc["description"]?.jsonPrimitive?.content ?: "",
        fields = fields
    )

    fun docToFormField(doc: JsonObject): FormField = FormField(
        id = doc["fieldId"]!!.jsonPrimitive.content,
        label = doc["label"]!!.jsonPrimitive.content,
        type = FieldType.valueOf(doc["fieldType"]!!.jsonPrimitive.content),
        placeholder = doc["placeholder"]?.jsonPrimitive?.content ?: "",
        visibility = doc["visibilityRule"]?.jsonObject?.let { docToVisibility(it) }
            ?: VisibilityRule.Always,
        validation = doc["validationRules"]?.jsonArray
            ?.map { docToValidation(it.jsonObject) } ?: emptyList()
    )

    private fun docToVisibility(doc: JsonObject): VisibilityRule {
        return when (val type = doc["@type"]?.jsonPrimitive?.content) {
            "AlwaysVisible" -> VisibilityRule.Always
            "WhenEquals" -> VisibilityRule.WhenEquals(
                fieldId = doc["fieldRef"]!!.jsonPrimitive.content,
                value = doc["matchValue"]!!.jsonPrimitive.content
            )
            "WhenRole" -> VisibilityRule.WhenRole(doc["role"]!!.jsonPrimitive.content)
            "WhenAny" -> VisibilityRule.WhenAny(
                doc["rules"]!!.jsonArray.map { docToVisibility(it.jsonObject) }
            )
            else -> throw IllegalArgumentException("Unknown visibility rule type: $type")
        }
    }

    private fun docToValidation(doc: JsonObject): ValidationRule {
        return when (val type = doc["@type"]?.jsonPrimitive?.content) {
            "RequiredRule" -> ValidationRule.Required
            "MinLengthRule" -> ValidationRule.MinLength(doc["minLength"]!!.jsonPrimitive.int)
            "MaxLengthRule" -> ValidationRule.MaxLength(doc["maxLength"]!!.jsonPrimitive.int)
            "RegexRule" -> ValidationRule.MatchesRegex(doc["pattern"]!!.jsonPrimitive.content)
            "RequiredWhenRule" -> ValidationRule.RequiredWhen(
                docToVisibility(doc["condition"]!!.jsonObject)
            )
            else -> throw IllegalArgumentException("Unknown validation rule type: $type")
        }
    }

    // ── OWL/Schema definition for TerminusDB ────────────────────────────────

    fun buildTerminusSchema(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "FormSchema")
            put("@documentation", buildJsonObject { put("@comment", "A form schema definition") })
            put("formId", "xsd:string")
            put("name", "xsd:string")
            put("description", "xsd:string")
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
            put("form", "FormSchema")
            put("visibilityRule", "VisibilityRule")
            put("validationRules", buildJsonObject {
                put("@type", "List")
                put("@class", "ValidationRule")
            })
        })
        // Visibility rule classes — @subdocument so TerminusDB embeds them inline
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "VisibilityRule")
            put("@abstract", buildJsonArray { })
            put("@subdocument", buildJsonArray { })
            put("@key", buildJsonObject { put("@type", "Random") })
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "AlwaysVisible")
            put("@inherits", "VisibilityRule")
            put("@subdocument", buildJsonArray { })
            put("@key", buildJsonObject { put("@type", "Random") })
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "WhenEquals")
            put("@inherits", "VisibilityRule")
            put("@subdocument", buildJsonArray { })
            put("@key", buildJsonObject { put("@type", "Random") })
            put("fieldRef", "xsd:string")
            put("matchValue", "xsd:string")
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "WhenRole")
            put("@inherits", "VisibilityRule")
            put("@subdocument", buildJsonArray { })
            put("@key", buildJsonObject { put("@type", "Random") })
            put("role", "xsd:string")
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "WhenAny")
            put("@inherits", "VisibilityRule")
            put("@subdocument", buildJsonArray { })
            put("@key", buildJsonObject { put("@type", "Random") })
            put("rules", buildJsonObject {
                put("@type", "List")
                put("@class", "VisibilityRule")
            })
        })
        // Validation rule classes — @subdocument so TerminusDB embeds them inline
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "ValidationRule")
            put("@abstract", buildJsonArray { })
            put("@subdocument", buildJsonArray { })
            put("@key", buildJsonObject { put("@type", "Random") })
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "RequiredRule")
            put("@inherits", "ValidationRule")
            put("@subdocument", buildJsonArray { })
            put("@key", buildJsonObject { put("@type", "Random") })
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "MinLengthRule")
            put("@inherits", "ValidationRule")
            put("@subdocument", buildJsonArray { })
            put("@key", buildJsonObject { put("@type", "Random") })
            put("minLength", "xsd:integer")
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "MaxLengthRule")
            put("@inherits", "ValidationRule")
            put("@subdocument", buildJsonArray { })
            put("@key", buildJsonObject { put("@type", "Random") })
            put("maxLength", "xsd:integer")
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "RegexRule")
            put("@inherits", "ValidationRule")
            put("@subdocument", buildJsonArray { })
            put("@key", buildJsonObject { put("@type", "Random") })
            put("pattern", "xsd:string")
        })
        add(buildJsonObject {
            put("@type", "Class")
            put("@id", "RequiredWhenRule")
            put("@inherits", "ValidationRule")
            put("@subdocument", buildJsonArray { })
            put("@key", buildJsonObject { put("@type", "Random") })
            put("condition", "VisibilityRule")
        })
    }
}
