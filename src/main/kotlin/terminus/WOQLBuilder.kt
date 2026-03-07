package miv.dev.ru.terminus

import kotlinx.serialization.json.*

/**
 * Minimal WOQL DSL builder. Produces the JSON format accepted by TerminusDB's WOQL endpoint.
 */
object WOQLBuilder {

    fun variable(name: String): JsonObject = buildJsonObject {
        put("@type", "woql:Variable")
        put("woql:variable_name", name)
    }

    fun iri(value: String): JsonObject = buildJsonObject {
        put("@value", value)
        put("@type", "xsd:anyURI")
    }

    fun string(value: String): JsonObject = buildJsonObject {
        put("@value", value)
        put("@type", "xsd:string")
    }

    fun triple(subject: JsonObject, predicate: JsonObject, obj: JsonObject): JsonObject =
        buildJsonObject {
            put("@type", "woql:Triple")
            put("woql:subject", subject)
            put("woql:predicate", predicate)
            put("woql:object", obj)
        }

    fun and(vararg queries: JsonObject): JsonObject = buildJsonObject {
        put("@type", "woql:And")
        put("woql:query_list", buildJsonArray {
            queries.forEachIndexed { i, q ->
                add(buildJsonObject {
                    put("@type", "woql:QueryListElement")
                    put("woql:index", buildJsonObject {
                        put("@value", i)
                        put("@type", "xsd:integer")
                    })
                    put("woql:query", q)
                })
            }
        })
    }

    fun eq(left: JsonObject, right: JsonObject): JsonObject = buildJsonObject {
        put("@type", "woql:Equals")
        put("woql:left", left)
        put("woql:right", right)
    }

    fun select(vararg vars: String, query: JsonObject): JsonObject = buildJsonObject {
        put("@type", "woql:Select")
        put("woql:variable_list", buildJsonArray {
            vars.forEach { v ->
                add(buildJsonObject {
                    put("@type", "woql:VariableListElement")
                    put("woql:variable", variable(v))
                })
            }
        })
        put("woql:query", query)
    }

    /** Find all FormField documents belonging to a specific FormSchema */
    fun fieldsForForm(formId: String): JsonObject = and(
        triple(variable("Field"), iri("rdf:type"), iri("@schema:FormField")),
        triple(variable("Field"), iri("@schema:form"), iri("FormSchema/$formId"))
    )

    /** Find FormField with a specific id */
    fun fieldById(fieldId: String): JsonObject = and(
        triple(iri("FormField/$fieldId"), iri("rdf:type"), iri("@schema:FormField")),
        triple(iri("FormField/$fieldId"), iri("@schema:fieldId"), variable("FieldId"))
    )
}
