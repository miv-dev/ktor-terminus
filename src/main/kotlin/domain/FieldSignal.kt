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
    val hints: List<String> = emptyList()
)