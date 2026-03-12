package miv.dev.ru.domain

import kotlinx.serialization.Serializable

@Serializable
data class FormSchema(
    val id: String,
    val name: String,
    val fields: List<FormField>,
    val submitAction: SubmitAction? = null
)

@Serializable
data class SubmitAction(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class FormField(
    val id: String,
    val label: String,
    val type: FieldType,
    val placeholder: String = "",
    val options: List<String> = emptyList(),
    val dataSource: DataSource? = null
)

@Serializable
data class DataSource(
    val url: String,
    val method: String = "POST",       // "GET" or "POST"
    val bodyParam: String = "",        // POST: JSON key for the trigger value, e.g. "country"
    val queryParam: String = "",       // GET: query param name
    val triggerField: String,          // which field's current value to inject, e.g. "country"
    val responsePath: String = "data"  // dot-separated path to the options array in the response
)

@Serializable
enum class FieldType {
    TEXT, EMAIL, NUMBER, SELECT, CHECKBOX, PHONE
}
