package miv.dev.ru.domain

import kotlinx.serialization.Serializable

@Serializable
data class FieldSignal(
    val fieldId: String,
    val visible: Boolean,
    val required: Boolean,
    val valid: Boolean? = null,
    val validationMessage: String? = null,
    val reason: String? = null
)
