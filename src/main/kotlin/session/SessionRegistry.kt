package miv.dev.ru.session

import java.util.concurrent.ConcurrentHashMap

class SessionRegistry {
    private val sessions = ConcurrentHashMap<String, FormSession>()

    fun register(id: String, session: FormSession) { sessions[id] = session }
    fun unregister(id: String) { sessions.remove(id) }
    fun get(id: String): FormSession? = sessions[id]
    fun all(): Collection<FormSession> = sessions.values
}