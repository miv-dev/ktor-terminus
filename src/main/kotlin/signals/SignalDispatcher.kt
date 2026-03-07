package miv.dev.ru.signals

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import miv.dev.ru.domain.FieldSignal
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class SignalDispatcher {
    private val log = LoggerFactory.getLogger(SignalDispatcher::class.java)

    // One SharedFlow per formId
    private val formFlows = ConcurrentHashMap<String, MutableSharedFlow<List<FieldSignal>>>()
    // Last known state per formId — emitted immediately to new subscribers
    private val lastState = ConcurrentHashMap<String, List<FieldSignal>>()

    private fun flowFor(formId: String): MutableSharedFlow<List<FieldSignal>> =
        formFlows.getOrPut(formId) { MutableSharedFlow(replay = 1) }

    fun subscribeForm(formId: String): SharedFlow<List<FieldSignal>> =
        flowFor(formId).asSharedFlow()

    suspend fun broadcast(formId: String, signals: List<FieldSignal>) {
        log.debug("Broadcasting ${signals.size} signals for form $formId")
        lastState[formId] = signals
        flowFor(formId).emit(signals)
    }

    fun lastKnownState(formId: String): List<FieldSignal>? = lastState[formId]
}
