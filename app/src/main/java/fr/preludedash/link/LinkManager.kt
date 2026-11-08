package fr.preludedash.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import fr.preludedash.data.CalStore
import fr.preludedash.data.IsrDecoder
import java.io.IOException
import java.util.UUID
import kotlin.math.min

/**
 * Liaison Bluetooth avec le module HC-05 branché sur le CN2 de l'ECU.
 * Tourne tant que l'appli téléphone OU l'écran Android Auto en a besoin.
 */
object LinkManager {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private const val MIN_PERIOD_MS = 50L  // 20 trames/s max
    private const val GAP_MS = 20L         // silence qui marque la fin d'une trame
    private const val TIMEOUT_MS = 300L    // pas de réponse = trame ratée
    private const val MAX_FAILS = 15       // trames ratées d'affilée avant de reconnecter

    private val clients = mutableSetOf<String>()
    private var worker: Worker? = null

    @Synchronized
    fun acquire(ctx: Context, client: String) {
        clients += client
        if (worker == null) startWorker(ctx.applicationContext)
    }

    @Synchronized
    fun release(client: String) {
        clients -= client
        if (clients.isEmpty()) stopWorker()
    }

    /** Relance la liaison (changement de module, permission accordée...). */
    @Synchronized
    fun restart(ctx: Context) {
        stopWorker()
        if (clients.isNotEmpty()) startWorker(ctx.applicationContext)
    }

    fun hasBtPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun startWorker(ctx: Context) {
        worker = Worker(ctx).also { it.start() }
    }

    private fun stopWorker() {
        worker?.shutdown()
        worker = null
        DataHub.setStatus(DataHub.State.IDLE, "Pas connecté")
    }

    /** Un fil d'exécution par session de liaison : l'ancien s'éteint proprement quand on relance. */
    private class Worker(private val ctx: Context) : Thread("ecu-link") {
        @Volatile
        private var active = true

        @Volatile
        private var socket: BluetoothSocket? = null

        fun shutdown() {
            active = false
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            interrupt()
        }

        override fun run() {
            while (active) {
                try {
                    runOnce()
                } catch (e: InterruptedException) {
                    // arrêt demandé
                } catch (e: Exception) {
                    if (active) {
                        DataHub.setStatus(
                            DataHub.State.ERROR,
                            "Liaison perdue, nouvel essai... (${e.message ?: "erreur"})",
                        )
                    }
                }
                if (active) {
                    try {
                        sleep(1500)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun runOnce() {
            val cal = CalStore.get(ctx)
            if (cal.demo) {
                runDemo()
                return
            }
            val address = cal.deviceAddress
            if (address == null) {
                DataHub.setStatus(DataHub.State.IDLE, "Choisis le module Bluetooth dans l'appli")
                return
            }
            if (!LinkManager.hasBtPermission(ctx)) {
                DataHub.setStatus(DataHub.State.ERROR, "Ouvre l'appli sur le tel pour autoriser le Bluetooth")
                return
            }
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                DataHub.setStatus(DataHub.State.ERROR, "Bluetooth du téléphone coupé")
                return
            }
            val device = adapter.getRemoteDevice(address)
            DataHub.setStatus(DataHub.State.CONNECTING, "Connexion à ${cal.deviceName ?: address}...")
            val s = connect(device)
            try {
                pollLoop(s, address)
            } finally {
                try {
                    s.close()
                } catch (_: Exception) {
                }
                socket = null
            }
        }

        @SuppressLint("MissingPermission")
        private fun connect(device: BluetoothDevice): BluetoothSocket {
            val attempts = listOf<() -> BluetoothSocket>(
                { device.createRfcommSocketToServiceRecord(LinkManager.SPP_UUID) },
                { device.createInsecureRfcommSocketToServiceRecord(LinkManager.SPP_UUID) },
                {
                    // Contournement classique pour certains HC-05 : canal RFCOMM 1 direct
                    device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                        .invoke(device, 1) as BluetoothSocket
                },
            )
            var last: Exception? = null
            for (make in attempts) {
                if (!active) break
                var s: BluetoothSocket? = null
                try {
                    s = make()
                    socket = s
                    s.connect()
                    return s
                } catch (e: Exception) {
                    last = e
                    try {
                        s?.close()
                    } catch (_: Exception) {
                    }
                }
            }
            throw last ?: IOException("connexion impossible")
        }

        private fun pollLoop(s: BluetoothSocket, address: String) {
            val input = s.inputStream
            val output = s.outputStream
            val buf = ByteArray(256)
            val drain = ByteArray(256)
            var fails = 0
            var frames = 0
            var hzStart = SystemClock.elapsedRealtime()
            DataHub.setStatus(DataHub.State.CONNECTED, "ECU connecté")

            while (active) {
                // Réglages modifiés dans l'appli ? (module changé ou mode démo)
                val cal = CalStore.get(ctx)
                if (cal.demo || cal.deviceAddress != address) return

                // On vide ce qui traîne, puis on demande une trame
                while (input.available() > 0) input.read(drain, 0, min(drain.size, input.available()))
                output.write(byteArrayOf(IsrDecoder.REQUEST))
                output.flush()

                val start = SystemClock.elapsedRealtime()
                var n = 0
                var lastByte = start
                while (active) {
                    val avail = input.available()
                    val now = SystemClock.elapsedRealtime()
                    if (avail > 0) {
                        val r = input.read(buf, n, min(avail, buf.size - n))
                        if (r < 0) throw IOException("flux fermé")
                        n += r
                        lastByte = now
                        if (n >= buf.size) break
                    } else {
                        if (n >= cal.frameLen && now - lastByte > LinkManager.GAP_MS) break
                        if (n in 1 until cal.frameLen && now - lastByte > 150) break
                        if (now - start > LinkManager.TIMEOUT_MS) break
                        sleep(2)
                    }
                }
                DataHub.lastFrameLen = n

                if (n >= cal.frameLen) {
                    fails = 0
                    frames++
                    DataHub.publish(IsrDecoder.decode(buf.copyOf(n), cal))
                    if (DataHub.state != DataHub.State.CONNECTED) {
                        DataHub.setStatus(DataHub.State.CONNECTED, "ECU connecté")
                    }
                } else {
                    fails++
                    if (fails >= LinkManager.MAX_FAILS) throw IOException("l'ECU ne répond pas, contact mis ?")
                    if (fails % 3 == 0) {
                        DataHub.setStatus(DataHub.State.CONNECTED, "Trame incomplète ($n/${cal.frameLen} octets)")
                    }
                }

                val now = SystemClock.elapsedRealtime()
                if (now - hzStart >= 1000) {
                    DataHub.hz = frames * 1000f / (now - hzStart)
                    frames = 0
                    hzStart = now
                }
                val spent = SystemClock.elapsedRealtime() - start
                if (spent < LinkManager.MIN_PERIOD_MS) sleep(LinkManager.MIN_PERIOD_MS - spent)
            }
        }

        private fun runDemo() {
            val demo = DemoSource()
            DataHub.setStatus(DataHub.State.DEMO, "Mode démo")
            DataHub.hz = 20f
            while (active) {
                val cal = CalStore.get(ctx)
                if (!cal.demo) return
                DataHub.publish(demo.next(cal))
                sleep(LinkManager.MIN_PERIOD_MS)
            }
        }
    }
}
