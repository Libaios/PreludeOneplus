package fr.preludedash.ui.stage

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import fr.preludedash.data.CalStore
import fr.preludedash.ui.DashRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Affiche le tableau de bord sur une surface : l'écran Android Auto ou l'aperçu du téléphone.
 *
 * Rendu par la carte graphique (Filament) : la scène 3D, puis le tableau de bord par-dessus,
 * à 30 images/s calées sur le rafraîchissement de l'écran, sur un fil dédié.
 * Si la 3D ne démarre pas sur un téléphone, repli automatique sur le dessin 2D d'avant.
 */
class DashSurface(context: Context, name: String) {
    companion object {
        private const val TAG = "DashSurface"

        /** 30 images/s : le flux vidéo d'Android Auto ne va en général pas plus vite. */
        private const val FRAME_NS = 1_000_000_000L / 30

        @Volatile
        private var libsLoaded = false

        private fun loadLibs() {
            synchronized(this) {
                if (libsLoaded) return
                Utils.init()
                Gltfio.init()
                libsLoaded = true
            }
        }
    }

    private val ctx = context.applicationContext
    private val thread = HandlerThread("dash-$name").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    var page = 0

    /** Écran plus large que la voiture (combiné) : la page 3D s'étale jusqu'aux bords. */
    @Volatile
    var wide = false

    @Volatile
    private var area: Rect? = null

    // Tout ce qui suit n'est utilisé que sur le fil de rendu
    private val dash = DashRenderer()
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var stage: Stage3D? = null
    private var hud: HudLayer? = null
    private var swapChain: SwapChain? = null
    private var glFailed = false
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var running = false
    private var choreographer: Choreographer? = null
    private var lastFrameNs = 0L
    private var lastUpdateNs = 0L

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            choreographer?.postFrameCallback(this)
            // Une image sur deux à 60 Hz (une sur quatre à 120 Hz) : rythme régulier
            if (frameTimeNanos - lastFrameNs < FRAME_NS - 4_000_000L) return
            lastFrameNs = frameTimeNanos
            try {
                drawFrame(frameTimeNanos)
            } catch (t: Throwable) {
                Log.e(TAG, "erreur d'affichage", t)
                if (!glFailed) fallBack()
            }
        }
    }

    /** La surface est prête (taille en pixels). */
    fun attach(s: Surface, w: Int, h: Int) {
        handler.post {
            if (swapChain != null) releaseSurface()
            surface = s
            width = w
            height = h
            if (!glFailed && engine == null) {
                try {
                    initEngine()
                } catch (t: Throwable) {
                    Log.e(TAG, "3D indisponible, passage en 2D", t)
                    destroyEngine()
                    glFailed = true
                }
            }
            if (!glFailed) {
                try {
                    swapChain = engine?.createSwapChain(s)
                    applySize()
                } catch (t: Throwable) {
                    Log.e(TAG, "surface 3D impossible, passage en 2D", t)
                    fallBack()
                }
            }
        }
    }

    fun resize(w: Int, h: Int) {
        handler.post {
            width = w
            height = h
            applySize()
        }
    }

    /** Zone utile de l'écran (hors barres d'Android Auto), null = toute la surface. */
    fun setArea(r: Rect?) {
        area = r?.let { Rect(it) }
    }

    /** La surface va disparaître : on la libère avant de rendre la main. */
    fun detach() {
        val done = CountDownLatch(1)
        handler.post {
            releaseSurface()
            done.countDown()
        }
        try {
            done.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    fun start() {
        handler.post {
            if (running) return@post
            running = true
            lastUpdateNs = 0L
            val c = choreographer ?: Choreographer.getInstance().also { choreographer = it }
            c.postFrameCallback(frame)
        }
    }

    fun stop() {
        handler.post {
            running = false
            choreographer?.removeFrameCallback(frame)
        }
    }

    /** Arrêt définitif (écran fermé). */
    fun release() {
        handler.post {
            running = false
            choreographer?.removeFrameCallback(frame)
            releaseSurface()
            destroyEngine()
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------ fil de rendu

    private fun initEngine() {
        loadLibs()
        val e = Engine.create()
        engine = e
        renderer = e.createRenderer().apply {
            clearOptions = Renderer.ClearOptions().apply {
                clear = true
                clearColor = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
            }
        }
        stage = Stage3D(e, ctx)
        hud = HudLayer(e, ctx, handler)
    }

    private fun applySize() {
        if (width <= 0 || height <= 0) return
        stage?.resize(width, height)
        hud?.resize(width, height)
    }

    private fun releaseSurface() {
        val e = engine
        val sc = swapChain
        if (e != null && sc != null) {
            try {
                e.destroySwapChain(sc)
                e.flushAndWait()
            } catch (t: Throwable) {
                Log.w(TAG, "libération de surface", t)
            }
        }
        swapChain = null
        surface = null
    }

    private fun destroyEngine() {
        val e = engine ?: return
        try {
            stage?.destroy()
            hud?.destroy()
            renderer?.let { e.destroyRenderer(it) }
            swapChain?.let { e.destroySwapChain(it) }
            e.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "nettoyage 3D incomplet", t)
        }
        stage = null
        hud = null
        renderer = null
        swapChain = null
        engine = null
    }

    /** Abandon de la 3D : on rend la surface au dessin 2D. */
    private fun fallBack() {
        glFailed = true
        val s = surface
        releaseSurface()
        destroyEngine()
        surface = s
    }

    private fun drawFrame(frameTimeNanos: Long) {
        val s = surface ?: return
        if (!s.isValid) return
        val snap = DashRenderer.snapshot(page, CalStore.get(ctx))
        val full = Rect(0, 0, width, height)
        val zone = area ?: full

        val sc = swapChain
        val st = stage
        val h = hud
        val r = renderer
        if (!glFailed && sc != null && st != null && h != null && r != null) {
            val dt = if (lastUpdateNs == 0L) 0f else (frameTimeNanos - lastUpdateNs) / 1e9f
            lastUpdateNs = frameTimeNanos
            val onScene = snap.page % DashRenderer.PAGES == 0
            if (onScene) st.update(dt, snap.data, DashRenderer.isLive(snap), snap.cal.redline)
            val k = h.scale
            val z = Rect((zone.left * k).toInt(), (zone.top * k).toInt(), (zone.right * k).toInt(), (zone.bottom * k).toInt())
            h.draw { c -> dash.draw(c, z, snap, overlay = onScene, wide = wide) }
            h.setFade(if (onScene) st.fade else 0f)
            if (r.beginFrame(sc, frameTimeNanos)) {
                if (onScene) r.render(st.view)
                r.render(h.view)
                r.endFrame()
            }
        } else if (glFailed) {
            val canvas = try {
                s.lockCanvas(null)
            } catch (e: Exception) {
                null
            } ?: return
            try {
                dash.draw(canvas, zone, snap, overlay = false, wide = wide)
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
        }
    }
}
