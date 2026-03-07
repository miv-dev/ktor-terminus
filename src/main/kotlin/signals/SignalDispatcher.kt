package miv.dev.ru.signals

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import miv.dev.ru.domain.FieldSignal
import java.util.concurrent.ConcurrentHashMap

class SignalDispatcher {
    private val formFlows = ConcurrentHashMap<String, MutableSharedFlow<FieldSignal>>()

    private fun mutableFlowFor(formId: String): MutableSharedFlow<FieldSignal> =
        formFlows.getOrPut(formId) { MutableSharedFlow(replay = 0) }

    fun flowFor(formId: String): SharedFlow<FieldSignal> =
        mutableFlowFor(formId).asSharedFlow()

    suspend fun emit(formId: String, signal: FieldSignal) {
        mutableFlowFor(formId).emit(signal)
    }

    suspend fun broadcast(formId: String, signals: List<FieldSignal>) {
        val flow = mutableFlowFor(formId)
        signals.forEach { flow.emit(it) }
    }
}
