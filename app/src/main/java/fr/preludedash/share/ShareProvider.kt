package fr.preludedash.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import fr.preludedash.card.CardSnapshots
import fr.preludedash.data.CalStore
import fr.preludedash.link.DataHub
import fr.preludedash.link.LinkManager
import fr.preludedash.music.MusicRelay
import fr.preludedash.shared.ShareContract

/**
 * Canal vers l'appli Prelude Carte : elle demande la carte 4 fois par seconde et
 * envoie les commandes musique. Réservé aux applis signées avec la même clé.
 */
class ShareProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            ShareContract.M_CARD -> {
                Lease.touch(ctx)
                CardSnapshots.from(DataHub.data, DataHub.state, DataHub.ageMs(), CalStore.get(ctx)).toBundle()
            }
            ShareContract.M_MUSIC -> {
                MusicRelay.start(ctx)
                when (arg) {
                    ShareContract.MUSIC_TOGGLE -> MusicRelay.toggle()
                    ShareContract.MUSIC_PLAY -> MusicRelay.play()
                    ShareContract.MUSIC_NEXT -> MusicRelay.next()
                    ShareContract.MUSIC_PREVIOUS -> MusicRelay.previous()
                }
                Bundle()
            }
            else -> null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** Garde la liaison ECU et le relais musique allumés tant que la carte est affichée. */
private object Lease {
    private const val IDLE_MS = 6000L
    private val main = Handler(Looper.getMainLooper())
    private var held = false
    private var last = 0L

    private val check = object : Runnable {
        override fun run() {
            synchronized(this@Lease) {
                if (SystemClock.elapsedRealtime() - last > IDLE_MS) {
                    LinkManager.release("share")
                    held = false
                } else {
                    main.postDelayed(this, 2000)
                }
            }
        }
    }

    @Synchronized
    fun touch(ctx: Context) {
        last = SystemClock.elapsedRealtime()
        if (!held) {
            held = true
            LinkManager.acquire(ctx, "share")
            MusicRelay.start(ctx)
            main.postDelayed(check, 2000)
        }
    }
}
