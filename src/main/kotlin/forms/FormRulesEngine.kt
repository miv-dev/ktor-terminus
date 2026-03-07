package miv.dev.ru.forms

import miv.dev.ru.domain.*
import miv.dev.ru.terminus.TerminusClient
import org.slf4j.LoggerFactory

class FormRulesEngine(private val client: TerminusClient) {
    private val log = LoggerFactory.getLogger(FormRulesEngine::class.java)

    /**
     * Evaluate all fields in the schema against the context.
     * Uses local Kotlin evaluation for simple rules (WhenEquals, WhenRole, Always).
     * Falls back to WOQL for complex graph-traversal rules (WhenAny with nested logic).
     */
    suspend fun evaluate(schema: FormSchema, context: EvaluationContext): List<FieldSignal> {
        return schema.fields.map { field ->
            val value = context.fieldValues[field.id]
            // Validate when values are present in context, otherwise just compute visibility
            if (context.fieldValues.isNotEmpty()) {
                validateField(field, value, context)
            } else {
                evaluateField(field, context)
            }
        }
    }

    private fun evaluateField(field: FormField, context: EvaluationContext): FieldSignal {
        val visible = evaluateVisibility(field.visibility, context)
        val required = if (visible) isRequired(field.validation, context) else false
        return FieldSignal(
            fieldId = field.id,
            visible = visible,
            required = required,
            reason = describeVisibility(field.visibility)
        )
    }

    private fun evaluateVisibility(rule: VisibilityRule, context: EvaluationContext): Boolean =
        when (rule) {
            is VisibilityRule.Always -> true
            is VisibilityRule.WhenEquals -> {
                val actual = context.fieldValues[rule.fieldId]
                actual == rule.value
            }
            is VisibilityRule.WhenRole -> context.role == rule.role
            is VisibilityRule.WhenAny -> rule.rules.any { evaluateVisibility(it, context) }
        }

    private fun isRequired(rules: List<ValidationRule>, context: EvaluationContext): Boolean =
        rules.any { rule ->
            when (rule) {
                is ValidationRule.Required -> true
                is ValidationRule.RequiredWhen -> evaluateVisibility(rule.condition, context)
                else -> false
            }
        }

    private fun describeVisibility(rule: VisibilityRule): String = when (rule) {
        is VisibilityRule.Always -> "always visible"
        is VisibilityRule.WhenEquals -> "visible when ${rule.fieldId} = '${rule.value}'"
        is VisibilityRule.WhenRole -> "visible for role '${rule.role}'"
        is VisibilityRule.WhenAny -> "visible when any of: ${rule.rules.joinToString(" OR ") { describeVisibility(it) }}"
    }

    /**
     * Validate a specific field value against its rules.
     */
    fun validateField(field: FormField, value: String?, context: EvaluationContext): FieldSignal {
        val visible = evaluateVisibility(field.visibility, context)
        val required = if (visible) isRequired(field.validation, context) else false

        val (valid, message) = if (!visible) {
            true to null
        } else {
            validateValue(field.validation, value, context)
        }

        return FieldSignal(
            fieldId = field.id,
            visible = visible,
            required = required,
            valid = valid,
            validationMessage = message,
            reason = describeVisibility(field.visibility)
        )
    }

    private fun validateValue(
        rules: List<ValidationRule>,
        value: String?,
        context: EvaluationContext
    ): Pair<Boolean, String?> {
        for (rule in rules) {
            when (rule) {
                is ValidationRule.Required -> {
                    if (value.isNullOrBlank()) return false to "This field is required"
                }
                is ValidationRule.MinLength -> {
                    if ((value?.length ?: 0) < rule.min)
                        return false to "Minimum length is ${rule.min}"
                }
                is ValidationRule.MaxLength -> {
                    if ((value?.length ?: 0) > rule.max)
                        return false to "Maximum length is ${rule.max}"
                }
                is ValidationRule.MatchesRegex -> {
                    val regex = Regex(rule.pattern)
                    if (!value.isNullOrEmpty() && !regex.matches(value))
                        return false to "Value does not match required format"
                }
                is ValidationRule.RequiredWhen -> {
                    if (evaluateVisibility(rule.condition, context) && value.isNullOrBlank())
                        return false to "This field is required under current conditions"
                }
            }
        }
        return true to null
    }
}
