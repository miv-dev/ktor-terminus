package miv.dev.ru.domain

import kotlinx.serialization.Serializable

@Serializable
data class FieldSignal(
    val fieldId: String,
    val visible: Boolean,
    val required: Boolean,
    val readOnly: Boolean = false,
    val valid: Boolean? = null,
    val validationMessage: String? = null,
    val hints: List<String> = emptyList(),
    val options: List<String> = emptyList(),  // dynamic select options, empty = use schema defaults
    val fetchOptions: Boolean = false         // ASP says to fetch external options for this field
)