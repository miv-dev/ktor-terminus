package miv.dev.ru.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FormSchema(
    val id: String,
    val name: String,
    val description: String = "",
    val fields: List<FormField>
)

@Serializable
data class FormField(
    val id: String,
    val label: String,
    val type: FieldType,
    val placeholder: String = "",
    val validation: List<ValidationRule> = emptyList(),
    val visibility: VisibilityRule = VisibilityRule.Always
)

@Serializable
enum class FieldType {
    TEXT, EMAIL, NUMBER, SELECT, CHECKBOX
}

@Serializable
sealed class VisibilityRule {
    @Serializable
    @SerialName("Always")
    object Always : VisibilityRule()

    @Serializable
    @SerialName("WhenEquals")
    data class WhenEquals(val fieldId: String, val value: String) : VisibilityRule()

    @Serializable
    @SerialName("WhenRole")
    data class WhenRole(val role: String) : VisibilityRule()

    @Serializable
    @SerialName("WhenAny")
    data class WhenAny(val rules: List<VisibilityRule>) : VisibilityRule()
}

@Serializable
sealed class ValidationRule {
    @Serializable
    @SerialName("Required")
    object Required : ValidationRule()

    @Serializable
    @SerialName("MinLength")
    data class MinLength(val min: Int) : ValidationRule()

    @Serializable
    @SerialName("MaxLength")
    data class MaxLength(val max: Int) : ValidationRule()

    @Serializable
    @SerialName("MatchesRegex")
    data class MatchesRegex(val pattern: String) : ValidationRule()

    @Serializable
    @SerialName("RequiredWhen")
    data class RequiredWhen(val condition: VisibilityRule) : ValidationRule()
}
