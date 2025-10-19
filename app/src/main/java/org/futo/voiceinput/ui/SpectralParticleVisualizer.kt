package org.futo.voiceinput.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Composable
fun SpectralParticleVisualizer(
    magnitude: Float,
    micRadiusPx: Float,
    modifier: Modifier = Modifier
) {
    val state = remember { CosmicFirestormState() }
    state.setMicRadius(micRadiusPx)
    val latestMagnitude by rememberUpdatedState(magnitude)

    var frameTick by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        var lastFrameTime = withFrameNanos { it }
        while (isActive) {
            val frameTime = withFrameNanos { it }
            val deltaSeconds = (frameTime - lastFrameTime) / 1_000_000_000f
            lastFrameTime = frameTime
            state.update(deltaSeconds, latestMagnitude)
            frameTick = frameTime
        }
    }

    Canvas(
        modifier = modifier.onSizeChanged { state.onSizeChanged(it) }
    ) {
        if (frameTick < 0L) {
            return@Canvas
        }
        state.draw(this)
    }
}

private class CosmicFirestormState {
    private val particles = Array(PARTICLE_POOL_SIZE) { Particle() }
    private val sparks = Array(SPARK_POOL_SIZE) { Spark() }
    private val random = Random(System.nanoTime())

    private var canvasSize: IntSize = IntSize.Zero
    private var center = Offset.Zero
    private var boundaryRadius = 0f
    private var boundaryRadiusSq = 0f
    private var micRadius = 0f
    private var occlusionRadius = 0f
    private var occlusionRadiusSq = 0f

    private var orbRadius = 0f
    private var orbTarget = 0f

    private var volume = 0f
    private var bass = 0f
    private var mids = 0f
    private var treble = 0f
    private var spawnTimer = 0f

    fun setMicRadius(radius: Float) {
        micRadius = radius * MIC_OCCLUSION_PADDING
        recalcOcclusionRadius()
    }

    fun onSizeChanged(size: IntSize) {
        if (size.width == 0 || size.height == 0) return
        canvasSize = size
        center = Offset(size.width / 2f, size.height / 2f)
        val minDimension = min(size.width, size.height).toFloat()
        val usableRadius = minDimension * 0.45f
        boundaryRadius = usableRadius
        boundaryRadiusSq = boundaryRadius * boundaryRadius
        recalcOcclusionRadius()
        orbRadius = boundaryRadius * 0.15f
        orbTarget = orbRadius
    }

    fun update(delta: Float, magnitudeInput: Float) {
        if (canvasSize == IntSize.Zero) return

        val magnitude = magnitudeInput.coerceIn(0f, 1f)
        volume = lerp(volume, magnitude, 0.18f)
        bass = lerp(bass, magnitude * 1.05f, 0.16f)
        mids = lerp(mids, magnitude * 0.92f + random.nextFloat() * 0.05f, 0.14f)
        treble = lerp(treble, magnitude * 0.7f + random.nextFloat() * 0.08f, 0.12f)

        val target = (volume * boundaryRadius * 0.7f) + 12f
        orbTarget = target
        orbRadius = lerp(orbRadius, orbTarget, 0.12f)

        spawnTimer += delta
        if (volume > VOLUME_THRESHOLD && spawnTimer >= SPAWN_INTERVAL) {
            spawnParticles()
            spawnTimer = 0f
        }

        val frameFactor = (delta * 60f).coerceIn(0.5f, 4f)
        particles.forEach { particle ->
            if (particle.active) particle.update(frameFactor, center, boundaryRadius, boundaryRadiusSq, sparks, random)
        }
        sparks.forEach { spark ->
            if (spark.active) spark.update(frameFactor)
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        if (canvasSize == IntSize.Zero) return

        drawRect(color = Color.Black)
        drawOrb()
        drawBoundary()

        particles.forEach { particle ->
            if (particle.active) particle.draw(this, center, boundaryRadius, occlusionRadiusSq)
        }
        sparks.forEach { spark ->
            if (spark.active) spark.draw(this, center, occlusionRadiusSq)
        }
    }

    private fun recalcOcclusionRadius() {
        occlusionRadius = when {
            boundaryRadius <= 0f -> micRadius
            micRadius == 0f -> 0f
            else -> min(micRadius, boundaryRadius * MIC_OCCLUSION_MAX_RATIO)
        }
        occlusionRadiusSq = occlusionRadius * occlusionRadius
    }

    private fun spawnParticles() {
        val count = (mids * 15f).roundToInt().coerceIn(2, 48)
        val speedBase = treble * 10f + 2f
        val sizeBase = bass * 8f + 2f

        repeat(count) {
            val particle = particles.firstOrNull { !it.active } ?: return@repeat
            val angle = random.nextFloat() * (PI.toFloat() * 2f)
            val speed = 1f + random.nextFloat() * speedBase
            val vx = cos(angle) * speed
            val vy = sin(angle) * speed
            val size = (1f + random.nextFloat()) * sizeBase
            val life = 180f + random.nextFloat() * 120f
            particle.init(center.x, center.y, vx, vy, size.coerceAtLeast(2f), life)
        }
    }

    private fun DrawScope.drawOrb() {
        val brightness = (volume * 2f).coerceIn(0f, 1f)
        val gradient = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color(red = 1f, green = 1f, blue = 122f / 255f, alpha = brightness * 0.9f),
                0.5f to Color(red = 1f, green = 124f / 255f, blue = 0f, alpha = brightness * 0.6f),
                1f to Color(red = 1f, green = 0f, blue = 0f, alpha = 0f)
            ),
            center = center,
            radius = max(orbRadius, 1f)
        )
        drawCircle(brush = gradient, center = center, radius = max(orbRadius, 1f))
    }

    private fun DrawScope.drawBoundary() {
        drawCircle(
            color = Color(red = 1f, green = 124f / 255f, blue = 0f, alpha = 0.25f),
            radius = boundaryRadius,
            center = center,
            style = Stroke(width = boundaryRadius * 0.02f)
        )
    }
}

private class Particle {
    var active = false
        private set

    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var size = 0f
    private var life = 0f
    private var maxLife = 0f

    fun init(originX: Float, originY: Float, velocityX: Float, velocityY: Float, radius: Float, lifespan: Float) {
        active = true
        x = originX
        y = originY
        vx = velocityX
        vy = velocityY
        size = radius
        life = lifespan
        maxLife = lifespan
    }

    fun update(
        frameFactor: Float,
        center: Offset,
        boundaryRadius: Float,
        boundaryRadiusSq: Float,
        sparks: Array<Spark>,
        random: Random
    ) {
        if (!active) return

        x += vx * frameFactor
        y += vy * frameFactor

        vx += (random.nextFloat() - 0.5f) * 0.15f * frameFactor
        vy += (random.nextFloat() - 0.5f) * 0.15f * frameFactor

        val friction = 0.99f.pow(frameFactor)
        vx *= friction
        vy *= friction

        life -= frameFactor * PARTICLE_DECAY_RATE
        if (life <= 0f) {
            active = false
            return
        }

        val dx = x - center.x
        val dy = y - center.y
        val distSq = dx * dx + dy * dy
        if (distSq > boundaryRadiusSq && distSq > 0f) {
            val dist = sqrt(distSq)
            val normalX = dx / dist
            val normalY = dy / dist
            val dot = vx * normalX + vy * normalY
            vx = (vx - 2f * dot * normalX) * 0.8f
            vy = (vy - 2f * dot * normalY) * 0.8f
            x = center.x + normalX * boundaryRadius
            y = center.y + normalY * boundaryRadius

            sparks.firstOrNull { !it.active }?.init(x, y, random)
        }
    }

    fun draw(scope: DrawScope, center: Offset, boundaryRadius: Float, occlusionRadiusSq: Float) = with(scope) {
        val progress = (life / maxLife).coerceIn(0f, 1f)
        val opacity = progress
        val dx = x - center.x
        val dy = y - center.y
        val distSq = dx * dx + dy * dy
        if (distSq < occlusionRadiusSq) {
            return@with
        }
        val distRatio = (sqrt(distSq) / boundaryRadius).coerceIn(0f, 1f)
        val color = fireColor(distRatio, opacity * 0.8f)
        val particleRadius = this@Particle.size * opacity

        drawCircle(
            color = color,
            radius = particleRadius,
            center = Offset(x, y),
            blendMode = BlendMode.Plus
        )
    }
}

private class Spark {
    var active = false
        private set

    private var x = 0f
    private var y = 0f
    private var life = 0f
    private var size = 0f

    fun init(originX: Float, originY: Float, random: Random) {
        active = true
        x = originX
        y = originY
        life = SPARK_MAX_LIFE
        size = random.nextFloat() * 3f + 2f
    }

    fun update(frameFactor: Float) {
        if (!active) return
        life -= frameFactor * SPARK_DECAY_RATE
        if (life <= 0f) {
            active = false
        }
    }

    fun draw(scope: DrawScope, center: Offset, occlusionRadiusSq: Float) = with(scope) {
        val opacity = (life / SPARK_MAX_LIFE).coerceIn(0f, 1f)
        val dx = x - center.x
        val dy = y - center.y
        if (dx * dx + dy * dy < occlusionRadiusSq) {
            return@with
        }
        val radius = this@Spark.size * opacity
        drawCircle(
            color = Color(red = 1f, green = 1f, blue = 180f / 255f, alpha = opacity),
            radius = radius,
            center = Offset(x, y),
            blendMode = BlendMode.Plus
        )
    }
}

private fun fireColor(ratio: Float, opacity: Float): Color {
    val clamped = ratio.coerceIn(0f, 1f)
    val (r, g, b) = when {
        clamped < 0.25f -> {
            val t = clamped / 0.25f
            Triple(255f, lerp(255f, 124f, t), lerp(122f, 0f, t))
        }
        clamped < 0.5f -> {
            val t = (clamped - 0.25f) / 0.25f
            Triple(255f, lerp(124f, 0f, t), 0f)
        }
        clamped < 0.75f -> {
            val t = (clamped - 0.5f) / 0.25f
            Triple(lerp(255f, 118f, t), 0f, 0f)
        }
        else -> {
            val t = (clamped - 0.75f) / 0.25f
            Triple(lerp(118f, 25f, t), 0f, 0f)
        }
    }
    return Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = opacity)
}

private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

private const val PARTICLE_POOL_SIZE = 500
private const val SPARK_POOL_SIZE = 100
private const val SPAWN_INTERVAL = 1f / 75f
private const val VOLUME_THRESHOLD = 0.09f
private const val PARTICLE_DECAY_RATE = 0.7f
private const val SPARK_DECAY_RATE = 3f
private const val SPARK_MAX_LIFE = 40f
private const val MIC_OCCLUSION_PADDING = 1.05f
private const val MIC_OCCLUSION_MAX_RATIO = 0.65f
