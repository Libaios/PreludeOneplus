package fr.preludedash.ui.stage

import android.content.Context
import android.graphics.Color
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import fr.preludedash.data.EngineData
import fr.preludedash.ui.Accent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Le monde 3D de la page d'accueil (Filament, sur la carte graphique).
 *
 * À l'arrêt, la Prelude tourne sur un plateau dans un atelier (plafond à hexagones, néon,
 * servante, pneus). Dès qu'elle roule, on bascule dans une rue de nuit qui défile à sa vitesse,
 * caméra de poursuite. Les lumières d'ambiance suivent le régime (turquoise, orange, rouge).
 * Un reflet de la voiture (copie symétrique sous le sol) donne l'effet de sol brillant ou mouillé.
 *
 * Tous les appels se font sur le fil de rendu. Décors : tools/decor (générés par programme).
 * Voiture : « Honda Prelude 1997 Free » par Black Snow (Sketchfab), licence CC BY 4.0.
 */
class Stage3D(private val engine: Engine, ctx: Context) {

    enum class Mode { GARAGE, ROUTE }

    companion object {
        private const val MODEL_LEN = 3.0101f
        private const val REAL_LEN = 4.52f
        private const val MODEL_CX = 0.0157f
        private const val MODEL_MIN_Y = 0.0200f
        private const val TT_H = 0.035f
        private const val WHEEL_R = 0.30f
        private const val FADE_S = 0.4f
        private const val STOP_TO_GARAGE_S = 3f
        private val WHEELS = arrayOf("Cylinder_Material.001_0", "Cylinder_Material.001_0.001")
    }

    val scene: Scene = engine.createScene()
    val view: View = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)

    private val materials = UbershaderProvider(engine)
    private val loader = AssetLoader(engine, materials, EntityManager.get())
    private val resources = ResourceLoader(engine)
    private val car: FilamentAsset
    private val mirror: FilamentAsset
    private val garage: FilamentAsset
    private val route: FilamentAsset
    private val garageIbl: KTX1Loader.IndirectLightBundle
    private val nightIbl: KTX1Loader.IndirectLightBundle

    private val tm = engine.transformManager
    private val rm = engine.renderableManager
    private val lm = engine.lightManager

    // Nœuds animés
    private val wheelInst = ArrayList<Int>()
    private val wheelBase = ArrayList<FloatArray>()
    private var turntable = 0
    private val scroll = IntArray(3)
    private val scrollPeriod = floatArrayOf(12f, 30f, 120f)
    private val accentMi = ArrayList<MaterialInstance>()

    // Circulation : deux voitures en face, deux devant sur la file de droite
    private val traffic = IntArray(4)
    private val trafficX = floatArrayOf(60f, 150f, 35f, 95f)
    private val trafficZ = floatArrayOf(-3.95f, -7.45f, 3.05f, 3.05f)
    private val trafficOncoming = booleanArrayOf(true, true, false, false)

    // Lumières
    private val garageLights = ArrayList<Int>()
    private val routeLights = ArrayList<Int>()
    private val accentLights = ArrayList<Int>()
    private val lampLights = ArrayList<Int>()
    private val lampSlots = ArrayList<FloatArray>() // x de base, z
    private val headLights = IntArray(2)

    // État
    var mode = Mode.GARAGE
        private set

    /** 0 = image visible, 1 = noir (fondu pendant le changement de décor). */
    var fade = 0f
        private set
    private var fadeDir = 0
    private var pending: Mode? = null
    private var inScene: Mode? = null
    private var time = 0f
    private var spin = 0.7f
    private var odo = 0f
    private var wheelAngle = 0f
    private var speed = 0f
    private var accel = 0f
    private var rpmSmooth = 0f
    private var stopTime = 99f
    private var aspect = 800.0 / 480.0

    private val carM = FloatArray(16)
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)

    init {
        view.scene = scene
        view.camera = camera
        camera.setExposure(1f)
        view.antiAliasing = View.AntiAliasing.NONE
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
            enabled = true
            sampleCount = 4
        }
        view.bloomOptions = View.BloomOptions().apply {
            enabled = true
            strength = 0.06f
            resolution = 360
            levels = 6
            threshold = true
        }
        view.setDynamicLightingOptions(2f, 150f)

        car = load(ctx, "prelude.glb")
        mirror = load(ctx, "prelude.glb")
        garage = load(ctx, "garage.glb")
        route = load(ctx, "route.glb")
        garageIbl = KTX1Loader.createIndirectLight(engine, readAsset(ctx, "garage_ibl.ktx"))
        nightIbl = KTX1Loader.createIndirectLight(engine, readAsset(ctx, "night_ibl.ktx"))
        garageIbl.indirectLight?.intensity = 1.0f
        nightIbl.indirectLight?.intensity = 0.4f

        // Ordre de dessin des surfaces transparentes : reflet, sol, plateau, ombre, puis le reste
        renderables(mirror, priority = 1, cast = false, receive = false)
        renderables(car, priority = 4, cast = true, receive = true)
        renderables(garage, priority = 4, cast = false, receive = true)
        renderables(route, priority = 4, cast = false, receive = true)
        priority(garage, "garage_floor", 2)
        priority(garage, "turntable_top", 3)
        priority(route, "road", 2)

        for (a in arrayOf(car, mirror)) {
            for (name in WHEELS) {
                val e = a.getFirstEntityByName(name)
                if (e == 0) continue
                val inst = tm.getInstance(e)
                val base = FloatArray(16)
                tm.getTransform(inst, base)
                wheelInst.add(inst)
                wheelBase.add(base)
            }
        }
        turntable = garage.getFirstEntityByName("turntable")
        scroll[0] = route.getFirstEntityByName("scroll_12")
        scroll[1] = route.getFirstEntityByName("scroll_30")
        scroll[2] = route.getFirstEntityByName("scroll_120")
        for (k in traffic.indices) traffic[k] = route.getFirstEntityByName("traffic_$k")
        for (name in arrayOf("accent_strip", "accent_ring")) {
            val e = garage.getFirstEntityByName(name)
            if (e == 0) continue
            val inst = rm.getInstance(e)
            if (inst == 0) continue
            for (p in 0 until rm.getPrimitiveCount(inst)) {
                val mi = rm.getMaterialInstanceAt(inst, p)
                if (mi.material.hasParameter("emissiveFactor") && accentMi.none { it === mi }) accentMi.add(mi)
            }
        }

        createLights()
        scene.addEntities(car.entities)
        scene.addEntities(mirror.entities)
        enter(Mode.GARAGE)
    }

    // ------------------------------------------------------------- chargement

    private fun load(ctx: Context, name: String): FilamentAsset {
        val a = loader.createAsset(readAsset(ctx, name)) ?: error("$name illisible")
        resources.loadResources(a)
        a.releaseSourceData()
        return a
    }

    private fun readAsset(ctx: Context, name: String): ByteBuffer {
        val bytes = ctx.assets.open(name).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            flip()
        }
    }

    private fun renderables(a: FilamentAsset, priority: Int, cast: Boolean, receive: Boolean) {
        for (e in a.renderableEntities) {
            val i = rm.getInstance(e)
            if (i == 0) continue
            rm.setPriority(i, priority)
            rm.setCastShadows(i, cast)
            rm.setReceiveShadows(i, receive)
        }
    }

    private fun priority(a: FilamentAsset, name: String, p: Int) {
        val e = a.getFirstEntityByName(name)
        if (e == 0) return
        val i = rm.getInstance(e)
        if (i != 0) rm.setPriority(i, p)
    }

    private fun light(type: LightManager.Type, setup: LightManager.Builder.() -> Unit): Int {
        val e = EntityManager.get().create()
        LightManager.Builder(type).apply(setup).build(engine, e)
        return e
    }

    private fun createLights() {
        // Atelier : lumière du plafond (avec ombres), deux spots sur la voiture, lueurs au sol
        garageLights += light(LightManager.Type.DIRECTIONAL) {
            color(1f, 1f, 1f)
            intensity(1.5f)
            direction(0.15f, -1f, 0.12f)
            castShadows(true)
            shadowOptions(LightManager.ShadowOptions().apply {
                mapSize = 1024
                shadowCascades = 1
                shadowFar = 20f
            })
        }
        for (x in floatArrayOf(-1.4f, 1.4f)) {
            garageLights += light(LightManager.Type.FOCUSED_SPOT) {
                color(1f, 0.97f, 0.92f)
                intensity(400f)
                position(x, 3.45f, 0f)
                direction(0f, -1f, 0f)
                spotLightCone(0.5f, 0.9f)
                falloff(8f)
            }
        }
        for (z in floatArrayOf(-3.3f, 3.3f)) {
            val e = light(LightManager.Type.POINT) {
                color(0.03f, 0.66f, 0.52f)
                intensity(70f)
                position(0f, 0.45f, z)
                falloff(5f)
            }
            garageLights += e
            accentLights += e
        }

        // Rue : clair de lune (avec ombres) et lampadaires qui défilent
        routeLights += light(LightManager.Type.DIRECTIONAL) {
            color(0.62f, 0.72f, 1f)
            intensity(0.15f)
            direction(-0.25f, -1f, 0.45f)
            castShadows(true)
            shadowOptions(LightManager.ShadowOptions().apply {
                mapSize = 1024
                shadowCascades = 1
                shadowFar = 25f
            })
        }
        // Lampadaires de droite tous les 30 m, ceux de gauche décalés de 15 m
        for (k in 0..4) lampSlots += floatArrayOf(30f * k, 2.9f)
        for (k in -1..3) lampSlots += floatArrayOf(15f + 30f * k, -7.3f)
        for (slot in lampSlots) {
            val e = light(LightManager.Type.POINT) {
                color(1f, 0.78f, 0.52f)
                intensity(5000f)
                position(slot[0], 7.6f, slot[1])
                falloff(28f)
            }
            routeLights += e
            lampLights += e
        }

        // Phares, dans les deux décors
        for (i in 0..1) {
            headLights[i] = light(LightManager.Type.FOCUSED_SPOT) {
                color(0.95f, 0.97f, 1f)
                intensity(0f)
                position(2.1f, 0.62f, if (i == 0) -0.58f else 0.58f)
                direction(1f, -0.07f, 0f)
                spotLightCone(0.2f, 0.5f)
                falloff(45f)
            }
            scene.addEntity(headLights[i])
        }
    }

    private fun enter(m: Mode) {
        if (inScene == m) return
        val garageOn = m == Mode.GARAGE
        if (inScene != null) {
            if (garageOn) {
                scene.removeEntities(route.entities)
                for (e in routeLights) scene.removeEntity(e)
            } else {
                scene.removeEntities(garage.entities)
                for (e in garageLights) scene.removeEntity(e)
            }
        }
        if (garageOn) {
            scene.addEntities(garage.entities)
            for (e in garageLights) scene.addEntity(e)
            scene.indirectLight = garageIbl.indirectLight
            view.fogOptions = View.FogOptions()
        } else {
            scene.addEntities(route.entities)
            for (e in routeLights) scene.addEntity(e)
            scene.indirectLight = nightIbl.indirectLight
            view.fogOptions = View.FogOptions().apply {
                enabled = true
                distance = 15f
                density = 0.03f
                height = 0f
                heightFalloff = 0.04f
                color = floatArrayOf(0.06f, 0.025f, 0.07f)
                // les immeubles qui arrivent au loin sortent de la brume au lieu d'apparaître d'un coup
                maximumOpacity = 0.98f
                // le ciel, le soleil et la silhouette de la ville restent nets
                cutOffDistance = 350f
            }
        }
        inScene = m
        mode = m
    }

    // ------------------------------------------------------------------ image

    fun resize(width: Int, height: Int) {
        view.viewport = Viewport(0, 0, width, height)
        aspect = width.toDouble() / max(1, height)
    }

    /** Avance l'animation de [dtIn] secondes avec les dernières données moteur. */
    fun update(dtIn: Float, d: EngineData, live: Boolean, redline: Int) {
        val dt = dtIn.coerceIn(0f, 0.1f)
        time += dt
        val rpm = if (live) d.rpm else 0
        val kmh = if (live) d.vssKmh else 0
        val prev = speed
        speed += (kmh / 3.6f - speed) * min(1f, dt * 3f)
        accel += ((speed - prev) / max(dt, 1e-3f) - accel) * min(1f, dt * 2f)
        rpmSmooth += (rpm - rpmSmooth) * min(1f, dt * 10f)

        // Atelier à l'arrêt, rue dès que ça roule, avec un fondu au noir entre les deux
        val moving = live && kmh >= 3
        stopTime = if (moving) 0f else stopTime + dt
        val want = when {
            moving -> Mode.ROUTE
            stopTime >= STOP_TO_GARAGE_S -> Mode.GARAGE
            else -> mode
        }
        if (want != mode && fadeDir == 0) {
            pending = want
            fadeDir = 1
        }
        if (fadeDir == 1) {
            fade += dt / FADE_S
            if (fade >= 1f) {
                fade = 1f
                pending?.let { enter(it) }
                pending = null
                fadeDir = -1
            }
        } else if (fadeDir == -1) {
            fade -= dt / FADE_S
            if (fade <= 0f) {
                fade = 0f
                fadeDir = 0
            }
        }

        odo += speed * dt
        wheelAngle += speed / WHEEL_R * dt
        if (mode == Mode.GARAGE) spin += dt * 0.28f

        val rpmK = (rpmSmooth / redline).coerceIn(0f, 1f)
        val limiter = live && rpm >= redline
        val flash = limiter && ((time * 8f).toInt() % 2 == 0)
        val engineOn = live && rpm > 300

        placeCar(rpmK)
        if (mode == Mode.ROUTE) moveTraffic(dt)
        updateLights(Accent.color(rpmSmooth.toInt(), redline, live), rpmK, limiter, flash, engineOn)
        placeCamera(rpmK)
    }

    private fun placeCar(rpmK: Float) {
        val g = mode == Mode.GARAGE
        val s = REAL_LEN / MODEL_LEN
        // Modèle remis à l'échelle, avant vers +x (le fichier regarde vers -x), roues au sol
        val base = floatArrayOf(
            -s, 0f, 0f, 0f,
            0f, s, 0f, 0f,
            0f, 0f, -s, 0f,
            s * MODEL_CX, -s * MODEL_MIN_Y, 0f, 1f,
        )
        val lift = if (g) TT_H else 0f
        val yaw = if (g) spin else 0f
        // Tangage : le nez se lève à l'accélération et plonge au freinage
        val pitch = if (g) 0f else (accel * 0.004f).coerceIn(-0.025f, 0.02f)
        // Vibration moteur discrète quand ça monte dans les tours
        val shake = 0.0025f * rpmK * rpmK * rpmK * sin(time * 53f)
        mul(rotZ(pitch), base, tmpA)
        mul(rotY(yaw), tmpA, tmpB)
        mul(translate(0f, lift + abs(pitch) * 1.2f + shake, 0f), tmpB, carM)
        tm.setTransform(tm.getInstance(car.root), carM)
        // Reflet : symétrie par rapport au sol
        val mirrorM = FloatArray(16)
        mul(scale(1f, -1f, 1f), translate(0f, -lift, 0f), tmpA)
        mul(translate(0f, lift, 0f), tmpA, tmpB)
        mul(tmpB, carM, mirrorM)
        tm.setTransform(tm.getInstance(mirror.root), mirrorM)

        val rot = rotY(wheelAngle)
        for (i in wheelInst.indices) {
            mul(wheelBase[i], rot, tmpA)
            tm.setTransform(wheelInst[i], tmpA)
        }
        if (g && turntable != 0) tm.setTransform(tm.getInstance(turntable), rotY(spin))
        if (!g) {
            for (k in 0..2) {
                if (scroll[k] == 0) continue
                tm.setTransform(tm.getInstance(scroll[k]), translate(-(odo % scrollPeriod[k]), 0f, 0f))
            }
        }
    }

    private fun moveTraffic(dt: Float) {
        for (k in traffic.indices) {
            val e = traffic[k]
            if (e == 0) continue
            // En face : 50 km/h vers nous. Devant : un peu moins vite que nous, ou 30 km/h à l'arrêt
            val rel = if (trafficOncoming[k]) -(13.9f + speed) else max(8f, speed * 0.9f + 1.5f) - speed
            var x = trafficX[k] + rel * dt
            if (x < -40f) x = 300f + Random.nextFloat() * 90f
            if (x > 400f) x = -35f
            trafficX[k] = x
            mul(translate(x, 0f, trafficZ[k]), rotY(if (trafficOncoming[k]) PI.toFloat() else 0f), tmpA)
            tm.setTransform(tm.getInstance(e), tmpA)
        }
    }

    private fun updateLights(accentColor: Int, rpmK: Float, limiter: Boolean, flash: Boolean, engineOn: Boolean) {
        val r = linear(Color.red(accentColor))
        val gr = linear(Color.green(accentColor))
        val b = linear(Color.blue(accentColor))
        val strength = (1.2f + 2.6f * rpmK * rpmK) * when {
            flash -> 1.6f
            limiter -> 0.25f
            else -> 1f
        }
        for (mi in accentMi) {
            mi.setParameter("emissiveFactor", r, gr, b)
            if (mi.material.hasParameter("emissiveStrength")) mi.setParameter("emissiveStrength", strength)
        }
        for (e in accentLights) {
            val i = lm.getInstance(e)
            lm.setColor(i, r, gr, b)
            lm.setIntensity(i, 70f * (0.6f + rpmK) * if (limiter && !flash) 0.3f else 1f)
        }

        // Phares : suivent la voiture (qui tourne sur le plateau au garage)
        val g = mode == Mode.GARAGE
        val yaw = if (g) spin else 0f
        val c = cos(yaw)
        val sn = sin(yaw)
        for (i in 0..1) {
            val z = if (i == 0) -0.58f else 0.58f
            val inst = lm.getInstance(headLights[i])
            lm.setPosition(inst, c * 2.1f + sn * z, 0.62f + (if (g) TT_H else 0f), -sn * 2.1f + c * z)
            lm.setDirection(inst, c, -0.07f, -sn)
            lm.setIntensity(inst, if (!engineOn) 0f else if (g) 450f else 1200f)
        }

        if (!g) {
            val shift = odo % 30f
            for (k in lampLights.indices) {
                val slot = lampSlots[k]
                lm.setPosition(lm.getInstance(lampLights[k]), slot[0] - shift, 7.6f, slot[1])
            }
        }
    }

    private fun placeCamera(rpmK: Float) {
        val shakeK = 0.012f * rpmK.pow(4)
        val jx = shakeK * sin(time * 41f)
        val jy = shakeK * sin(time * 37f + 1f)
        if (mode == Mode.GARAGE) {
            val ang = PI / 4 + 0.10 * sin(time * 0.15)
            val dist = 6.5 + 0.2 * sin(time * 0.11)
            camera.setProjection(38.4, aspect, 0.1, 60.0, Camera.Fov.VERTICAL)
            camera.lookAt(
                dist * cos(ang) + jx, 1.45 + jy, dist * sin(ang),
                0.0, 0.62, 0.0,
                0.0, 1.0, 0.0,
            )
        } else {
            val sp = (speed * 3.6f / 160f).coerceIn(0f, 1f)
            val ang = PI + 0.16 + 0.04 * sin(time * 0.37)
            val dist = 7.0 - 0.6 * sp + (accel * 0.05f).coerceIn(-0.3f, 0.5f)
            camera.setProjection(38.0 + 10.0 * sp, aspect, 0.2, 1200.0, Camera.Fov.VERTICAL)
            // Visée un peu haute : on voit le soleil couchant au bout de la rue
            camera.lookAt(
                1.0 + dist * cos(ang) + jx, 1.9 + jy, dist * sin(ang),
                2.2, 1.3, 0.1,
                0.0, 1.0, 0.0,
            )
        }
    }

    fun destroy() {
        scene.removeEntities(car.entities)
        scene.removeEntities(mirror.entities)
        scene.removeEntities(garage.entities)
        scene.removeEntities(route.entities)
        for (a in arrayOf(car, mirror, garage, route)) loader.destroyAsset(a)
        resources.destroy()
        loader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        for (e in garageLights + routeLights + headLights.toList()) {
            scene.removeEntity(e)
            lm.destroy(e)
            EntityManager.get().destroy(e)
        }
        scene.indirectLight = null
        for (bundle in arrayOf(garageIbl, nightIbl)) {
            bundle.indirectLight?.let { engine.destroyIndirectLight(it) }
            bundle.cubemap?.let { engine.destroyTexture(it) }
        }
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
    }

    // ---------------------------------------------------------------- matrices

    private fun linear(c: Int): Float = (c / 255f).pow(2.2f)

    private fun translate(x: Float, y: Float, z: Float) = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f,
    )

    private fun scale(x: Float, y: Float, z: Float) = floatArrayOf(
        x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f, 0f, 0f, 0f, 1f,
    )

    private fun rotY(a: Float): FloatArray {
        val c = cos(a)
        val s = sin(a)
        return floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
    }

    private fun rotZ(a: Float): FloatArray {
        val c = cos(a)
        val s = sin(a)
        return floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }

    /** out = a x b (matrices 4x4 en colonnes). */
    private fun mul(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = sum
            }
        }
    }
}
