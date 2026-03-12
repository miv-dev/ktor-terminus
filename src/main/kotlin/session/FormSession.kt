package miv.dev.ru.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import miv.dev.ru.api.ExternalDataClient
import miv.dev.ru.asp.AspRulesEngine
import miv.dev.ru.domain.FieldSignal
import miv.dev.ru.domain.FormSchema
import miv.dev.ru.domain.InputEvent
import miv.dev.ru.domain.RuleSet
import miv.dev.ru.signals.SignalDispatcher
import java.util.concurrent.ConcurrentHashMap

class FormSession(
    val sessionId: String,
    val formId: String,
    private val schema: FormSchema,
    private val rules: RuleSet,
    private val engine: AspRulesEngine,
    private val dispatcher: SignalDispatcher,
    private val externalDataClient: ExternalDataClient
) {
    val currentValues = ConcurrentHashMap<String, String>()
    var role: String = "user"

    private val inputChannel = Channel<InputEvent>(capacity = Channel.BUFFERED)
    private val fieldIndex = schema.fields.associateBy { it.id }

    fun startProcessor(scope: CoroutineScope): Job = scope.launch {
        for (event in inputChannel) {
            when (event) {
                is InputEvent.FieldChanged -> {
                    currentValues[event.fieldId] = event.value
                    // Drain any additional queued FieldChanged events (coalesce fast typing)
                    var next = inputChannel.tryReceive().getOrNull()
                    while (next != null) {
                        if (next is InputEvent.FieldChanged) {
                            currentValues[next.fieldId] = next.value
                        }
                        next = inputChannel.tryReceive().getOrNull()
                    }
                    evaluate()
                }
                is InputEvent.FieldBlurred -> evaluate()
                is InputEvent.SessionStarted -> {
                    role = event.role
                    evaluate()
                }
                is InputEvent.FormSubmitAttempted -> {
                    val submitAllowed = evaluate()
                    if (submitAllowed) performSubmit()
                }
            }
        }
    }

    /** Returns true if submit_allowed is in the answer set. */
    private suspend fun evaluate(): Boolean {
        val signals = engine.evaluate(schema, rules, currentValues.toMap(), role)
        val enriched = enrichDynamicOptions(signals)
        enriched.forEach { dispatcher.emit(formId, it) }
        return enriched.find { it.fieldId == "__submit__" }?.visible == true
    }

    private suspend fun performSubmit() {
        val action = schema.submitAction ?: return
        val result = externalDataClient.submitForm(action, currentValues.toMap())
        dispatcher.emit(formId, FieldSignal(
            fieldId = "__submit__",
            visible = true,
            required = false,
            hints = listOf(if (result.success) "submitted" else "submit_error"),
            validationMessage = "${result.statusCode}: ${result.body.take(120)}"
        ))
    }

    /** For every field where ASP emitted fetch_options(...), fetch options from the external API. */
    private suspend fun enrichDynamicOptions(signals: List<FieldSignal>): List<FieldSignal> =
        signals.map { signal ->
            if (!signal.fetchOptions) return@map signal
            val source = fieldIndex[signal.fieldId]?.dataSource ?: return@map signal
            val triggerValue = currentValues[source.triggerField]?.takeIf { it.isNotBlank() }
                ?: return@map signal
            signal.copy(options = externalDataClient.fetchOptions(source, triggerValue))
        }

    suspend fun onInput(event: InputEvent) = inputChannel.send(event)

    fun close() = inputChannel.close()
}
