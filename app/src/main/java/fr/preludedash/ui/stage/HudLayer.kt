package fr.preludedash.ui.stage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Calque 2D du tableau de bord (chiffres, jauges) posé sur la scène 3D.
 *
 * Le tableau de bord est dessiné comme avant avec un Canvas Android dans une image, envoyée
 * à la carte graphique et affichée sur un rectangle plein écran, par-dessus la 3D. Les couleurs
 * de l'image sont recopiées telles quelles (pas de traitement d'image sur ce calque).
 * Matériau : tools/decor/hud.mat, compilé avec matc (Filament 1.75.1).
 */
class HudLayer(private val engine: Engine, ctx: Context, private val handler: Handler) {
    companion object {
        /** Largeur maximale de l'image du tableau de bord (au-delà, la carte graphique l'agrandit). */
        private const val MAX_W = 1280
    }

    val view: View = engine.createView()
    private val scene: Scene = engine.createScene()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val material: Material
    private val mi: MaterialInstance
    private val vb: VertexBuffer
    private val ib: IndexBuffer
    private val quad = EntityManager.get().create()
    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )

    private var texture: Texture? = null
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val buffers = arrayOfNulls<ByteBuffer>(2)
    private val busy = BooleanArray(2)
    private var next = 0

    /** Taille de l'image par rapport à la surface (1 = même résolution). */
    var scale = 1f
        private set

    init {
        camera.setProjection(Camera.Projection.ORTHO, -1.0, 1.0, -1.0, 1.0, 0.0, 10.0)
        camera.lookAt(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        view.camera = camera
        view.scene = scene
        view.isPostProcessingEnabled = false
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.setShadowingEnabled(false)

        val payload = ctx.assets.open("hud.filamat").use { it.readBytes() }
        val pb = ByteBuffer.allocateDirect(payload.size).order(ByteOrder.nativeOrder()).put(payload)
        pb.flip()
        material = Material.Builder().payload(pb, payload.size).build(engine)
        mi = material.createInstance()
        mi.setParameter("fade", 0f)

        // Rectangle plein écran ; la ligne du haut de l'image va en haut de l'écran
        val verts = floatArrayOf(
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
        )
        val vbuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
        vbuf.asFloatBuffer().put(verts)
        vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT2, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 8, 16)
            .build(engine)
        vb.setBufferAt(engine, 0, vbuf)
        val idx = shortArrayOf(0, 2, 1, 1, 2, 3)
        val ibuf = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder())
        ibuf.asShortBuffer().put(idx)
        ib = IndexBuffer.Builder()
            .indexCount(6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        ib.setBuffer(engine, ibuf)
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, 6)
            .material(0, mi)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, quad)
        scene.addEntity(quad)
    }

    /** Adapte l'image à la taille de la surface. */
    fun resize(width: Int, height: Int) {
        view.viewport = Viewport(0, 0, width, height)
        val w = min(width, MAX_W).coerceAtLeast(1)
        val h = max(1, (height * w / max(1, width).toFloat()).roundToInt())
        scale = w / max(1, width).toFloat()
        if (bitmap?.width == w && bitmap?.height == h) return

        val old = texture
        val tex = Texture.Builder()
            .width(w)
            .height(h)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8)
            .build(engine)
        mi.setParameter("hud", tex, sampler)
        texture = tex
        old?.let { engine.destroyTexture(it) }
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { canvas = Canvas(it) }
        for (i in 0..1) {
            buffers[i] = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            busy[i] = false
        }
    }

    /** Redessine l'image avec [block] (Canvas effacé, transparent) puis l'envoie à la carte graphique. */
    fun draw(block: (Canvas) -> Unit) {
        val b = bitmap ?: return
        val c = canvas ?: return
        val tex = texture ?: return
        b.eraseColor(0)
        block(c)
        // Deux tampons : on n'écrit jamais dans celui que la carte graphique est en train de lire
        val i = if (!busy[next]) next else if (!busy[1 - next]) 1 - next else return
        val buf = buffers[i] ?: return
        buf.clear()
        b.copyPixelsToBuffer(buf)
        buf.rewind()
        busy[i] = true
        next = 1 - i
        tex.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE, 1, 0, 0, 0, handler) {
                busy[i] = false
            },
        )
    }

    /** Fondu au noir de toute l'image (changement de décor). */
    fun setFade(f: Float) {
        mi.setParameter("fade", f.coerceIn(0f, 1f))
    }

    fun destroy() {
        scene.removeEntity(quad)
        engine.destroyEntity(quad)
        EntityManager.get().destroy(quad)
        engine.destroyVertexBuffer(vb)
        engine.destroyIndexBuffer(ib)
        engine.destroyMaterialInstance(mi)
        engine.destroyMaterial(material)
        texture?.let { engine.destroyTexture(it) }
        texture = null
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        bitmap = null
        canvas = null
    }
}
