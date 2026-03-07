package miv.dev.ru.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import miv.dev.ru.asp.AspRulesEngine
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
    private val dispatcher: SignalDispatcher
) {
    val currentValues = ConcurrentHashMap<String, String>()
    var role: String = "user"

    private val inputChannel = Channel<InputEvent>(capacity = Channel.BUFFERED)

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
                is InputEvent.FormSubmitAttempted -> evaluate()
            }
        }
    }

    private suspend fun evaluate() {
        val signals = engine.evaluate(schema, rules, currentValues.toMap(), role)
        signals.forEach { dispatcher.emit(formId, it) }
    }

    suspend fun onInput(event: InputEvent) = inputChannel.send(event)

    fun close() = inputChannel.close()
}