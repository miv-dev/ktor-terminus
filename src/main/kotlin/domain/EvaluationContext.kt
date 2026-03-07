package miv.dev.ru.domain

import kotlinx.serialization.Serializable

@Serializable
data class EvaluationContext(
    val role: String = "user",
    val fieldValues: Map<String, String> = emptyMap()
)
