package miv.dev.ru.domain

import kotlinx.serialization.Serializable

@Serializable
data class FormSchema(
    val id: String,
    val name: String,
    val fields: List<FormField>
)

@Serializable
data class FormField(
    val id: String,
    val label: String,
    val type: FieldType,
    val placeholder: String = "",
    val options: List<String> = emptyList()
)

@Serializable
enum class FieldType {
    TEXT, EMAIL, NUMBER, SELECT, CHECKBOX, PHONE
}
