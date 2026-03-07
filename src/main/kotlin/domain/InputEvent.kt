package miv.dev.ru.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class InputEvent {

    @Serializable
    @SerialName("FieldChanged")
    data class FieldChanged(val fieldId: String, val value: String) : InputEvent()

    @Serializable
    @SerialName("FieldBlurred")
    data class FieldBlurred(val fieldId: String) : InputEvent()

    @Serializable
    @SerialName("FormSubmitAttempted")
    data class FormSubmitAttempted(val sessionId: String) : InputEvent()

    @Serializable
    @SerialName("SessionStarted")
    data class SessionStarted(val role: String = "user") : InputEvent()
}