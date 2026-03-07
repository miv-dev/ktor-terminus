package miv.dev.ru.domain

import kotlinx.serialization.Serializable

@Serializable
data class RuleSet(
    val formId: String,
    val lpContent: String
)