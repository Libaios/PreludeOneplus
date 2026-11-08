package fr.preludedash.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import fr.preludedash.ui.stage.DashSurface
import kotlin.math.min

/**
 * Tableau de bord affiché sur le téléphone : exactement le même rendu que dans la voiture.
 * [fill] : prend toute la place (combiné plein écran), et la page 3D s'étale sur toute la largeur.
 * Sinon : aperçu au format de l'écran de la voiture.
 */
class DashView(
    context: Context,
    name: String = "phone",
    private val fill: Boolean = false,
) : TextureView(context), TextureView.SurfaceTextureListener {

    private val dash = DashSurface(context, name).apply { wide = fill }
    private var surface: Surface? = null

    var page: Int
        get() = dash.page
        set(value) {
            dash.page = value
        }

    /** En pause : plus aucune image dessinée (combiné en veille). */
    var paused = false
        set(value) {
            field = value
            if (value) dash.stop() else if (surface != null && windowVisibility == VISIBLE) dash.start()
        }

    init {
        surfaceTextureListener = this
        setOnClickListener { page = (page + 1) % DashRenderer.PAGES }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(st)
        surface = s
        dash.attach(s, width, height)
        if (!paused) dash.start()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        dash.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        dash.stop()
        dash.detach()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    // Appli en arrière-plan : on arrête de dessiner (la voiture a son propre affichage)
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && surface != null && !paused) dash.start() else dash.stop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dash.release()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (fill) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = (resources.displayMetrics.heightPixels * 0.8f).toInt()
        val h = min((w * DashRenderer.VH / DashRenderer.VW).toInt(), maxH)
        setMeasuredDimension(w, h)
    }
}
