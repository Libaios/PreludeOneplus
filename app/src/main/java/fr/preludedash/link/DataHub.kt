package fr.preludedash.link

import android.os.SystemClock
import fr.preludedash.data.EngineData
import java.util.concurrent.CopyOnWriteArraySet

/** Point central : dernière trame décodée + état de la liaison, lu par le tel et par Android Auto. */
object DataHub {
    enum class State { IDLE, CONNECTING, CONNECTED, ERROR, DEMO }

    @Volatile
    var data: EngineData = EngineData()
        private set

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var message: String = "Pas connecté"
        private set

    @Volatile
    var hz: Float = 0f

    @Volatile
    var lastFrameLen: Int = 0

    @Volatile
    private var lastUpdate: Long = 0L

    private val listeners = CopyOnWriteArraySet<Runnable>()

    fun addListener(l: Runnable) {
        listeners.add(l)
    }

    fun removeListener(l: Runnable) {
        listeners.remove(l)
    }

    fun publish(d: EngineData) {
        data = d
        lastUpdate = SystemClock.elapsedRealtime()
        fire()
    }

    fun setStatus(s: State, msg: String) {
        state = s
        message = msg
        fire()
    }

    /** Âge de la dernière trame en ms (très grand si jamais reçue). */
    fun ageMs(): Long =
        if (lastUpdate == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastUpdate

    private fun fire() {
        for (l in listeners) {
            try {
                l.run()
            } catch (_: Exception) {
            }
        }
    }
}
