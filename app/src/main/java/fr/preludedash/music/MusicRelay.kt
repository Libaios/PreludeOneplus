package fr.preludedash.music

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Relais vers l'appli de musique en cours (Spotify de préférence) : lecture, pause,
 * piste suivante/précédente, et titre en cours pour l'afficher sur la carte.
 * Nécessite l'accès aux notifications pour Prelude Dash.
 */
object MusicRelay {
    private const val SPOTIFY = "com.spotify.music"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Runnable>()
    private var app: Context? = null
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null

    @Volatile
    var title: String? = null
        private set

    @Volatile
    var artist: String? = null
        private set

    @Volatile
    var playing: Boolean = false
        private set

    /** Pochette réduite (pour le plein écran), si l'appli de musique la fournit. */
    @Volatile
    var art: Bitmap? = null
        private set

    @Volatile
    private var durationMs = 0L

    @Volatile
    private var positionMs = 0L

    @Volatile
    private var positionAt = 0L

    @Volatile
    private var positionSpeed = 1f

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            readMeta(metadata)
            fire()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            readState(state)
            fire()
        }

        override fun onSessionDestroyed() {
            refresh()
        }
    }

    fun hasAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, MusicListenerService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Démarre le suivi si l'accès est donné. Sans effet sinon (la carte marche quand même). */
    fun start(ctx: Context) {
        main.post {
            val c = ctx.applicationContext
            app = c
            if (manager != null || !hasAccess(c)) {
                refresh()
                return@post
            }
            try {
                val m = c.getSystemService(MediaSessionManager::class.java)
                val comp = ComponentName(c, MusicListenerService::class.java)
                m.addOnActiveSessionsChangedListener(sessionsListener, comp, main)
                manager = m
                pick(m.getActiveSessions(comp))
            } catch (_: SecurityException) {
                manager = null
            }
        }
    }

    fun refresh() {
        main.post {
            val c = app ?: return@post
            val m = manager
            if (m == null) {
                if (hasAccess(c)) start(c)
                return@post
            }
            try {
                pick(m.getActiveSessions(ComponentName(c, MusicListenerService::class.java)))
            } catch (_: SecurityException) {
            }
        }
    }

    private fun pick(list: List<MediaController>) {
        val own = app?.packageName
        val others = list.filter { it.packageName != own }
        val chosen = others.firstOrNull { it.packageName == SPOTIFY } ?: others.firstOrNull()
        if (chosen?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(controllerCallback)
        controller = chosen
        if (chosen != null) {
            chosen.registerCallback(controllerCallback, main)
            readMeta(chosen.metadata)
            readState(chosen.playbackState)
        } else {
            title = null
            artist = null
            art = null
            durationMs = 0L
            playing = false
        }
        fire()
    }

    private fun readMeta(m: MediaMetadata?) {
        val newTitle = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val trackChanged = newTitle != title || art == null
        title = newTitle
        artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (trackChanged) {
            val src = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            art = try {
                src?.let { Bitmap.createScaledBitmap(it, 128, 128, true) }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun readState(st: PlaybackState?) {
        playing = st?.state == PlaybackState.STATE_PLAYING
        if (st != null) {
            positionMs = st.position
            positionAt = st.lastPositionUpdateTime
            positionSpeed = st.playbackSpeed
        }
    }

    /** Avancement dans le morceau (0 à 1), ou null si l'appli de musique ne le donne pas. */
    fun progress(): Float? {
        val d = durationMs
        if (d <= 0L || title == null) return null
        var pos = positionMs
        if (playing && positionAt > 0L) pos += ((SystemClock.elapsedRealtime() - positionAt) * positionSpeed).toLong()
        return (pos.toFloat() / d).coerceIn(0f, 1f)
    }

    /** Morceau en cours, découpé pour les deux lignes de la carte. */
    data class Now(val title: String, val artistLine: String)

    /** Ligne unique (écran du téléphone), ou null s'il n'y a pas de musique. */
    fun line(): String? {
        val n = now() ?: return null
        return "${n.title} - ${n.artistLine}"
    }

    /** Titre et artiste (avec l'état lecture/pause), ou null s'il n'y a pas de musique. */
    fun now(): Now? {
        val t = title ?: return null
        val icon = if (playing) "▶" else "❚❚"
        val a = artist
        return Now(t, if (a.isNullOrBlank()) icon else "$icon  $a")
    }

    fun toggle() = main.post {
        val c = controller ?: return@post
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun play() = main.post { controller?.transportControls?.play() }

    fun next() = main.post { controller?.transportControls?.skipToNext() }

    fun previous() = main.post { controller?.transportControls?.skipToPrevious() }

    fun addListener(l: Runnable) {
        listeners.add(l)
    }

    fun removeListener(l: Runnable) {
        listeners.remove(l)
    }

    private fun fire() {
        for (l in listeners) {
            try {
                l.run()
            } catch (_: Exception) {
            }
        }
    }
}
