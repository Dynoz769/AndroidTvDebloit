package io.github.dynoz769.debloithome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The "Layan.OS" intro: light streaks race across the screen, the logo slams in
 * letter by letter, a shockwave and sparks burst out, a shine sweeps over the
 * logo, the credit fades in, then everything zooms away to reveal home.
 *
 * Every frame is drawn from [time], so the intro plays at full speed even when
 * the TV has system animations turned off.
 */
class IntroView(context: Context) : View(context) {

    var onFinished: (() -> Unit)? = null

    /** Seconds since the intro started. */
    var time = 0f
        set(value) {
            field = value
            alpha = 1f - easeInCubic(progress(value, OUTRO_AT, OUTRO_DURATION))
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private var startNanos = 0L
    private var running = false
    private var finished = false

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font(context, R.font.orbitron_black, Typeface.DEFAULT_BOLD)
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = titlePaint.typeface }
    private val creditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font(context, R.font.orbitron_medium, Typeface.DEFAULT)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint()
    private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineMatrix = Matrix()

    private val letterX = FloatArray(TITLE.length)
    private val letterW = FloatArray(TITLE.length)
    private var titleWidth = 0f
    private var titleLeft = 0f
    private var baseline = 0f
    private var capHeight = 0f
    private var cx = 0f
    private var cy = 0f
    private var diagonal = 0f

    private val random = Random(2024)
    private val streaks = List(16) { Streak(random) }
    private val sparks = List(170) { Spark(random) }
    private val embers = List(50) { Ember(random) }

    fun start() {
        if (running || finished) return
        running = true
        startNanos = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** Fades out quickly, for when a remote button is pressed. */
    fun skip() {
        if (finished) return
        stopFrames()
        animate().alpha(0f).setDuration(250).withEndAction { finish() }.start()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (startNanos == 0L) startNanos = frameTimeNanos
            time = (frameTimeNanos - startNanos) / 1e9f
            if (time >= TOTAL) finish() else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopFrames() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    private fun finish() {
        if (finished) return
        finished = true
        stopFrames()
        onFinished?.invoke()
    }

    override fun onDetachedFromWindow() {
        stopFrames()
        super.onDetachedFromWindow()
    }

    // Lets the fade-out apply alpha per draw call instead of through an offscreen layer.
    override fun hasOverlappingRendering() = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h * 0.46f
        diagonal = hypot(w.toFloat(), h.toFloat())

        titlePaint.textSize = 100f
        titlePaint.textSize = min(100f * w * 0.6f / titlePaint.measureText(TITLE), h * 0.2f)
        shinePaint.textSize = titlePaint.textSize
        titleWidth = titlePaint.measureText(TITLE)
        titleLeft = cx - titleWidth / 2
        for (i in TITLE.indices) {
            letterX[i] = titleLeft + titlePaint.measureText(TITLE, 0, i)
            letterW[i] = titlePaint.measureText(TITLE, i, i + 1)
        }
        val bounds = Rect()
        titlePaint.getTextBounds("L", 0, 1, bounds)
        capHeight = bounds.height().toFloat()
        baseline = cy + capHeight / 2
        creditPaint.textSize = max(titlePaint.textSize * 0.16f, 12 * dp)

        glowPaint.shader = RadialGradient(
            cx, cy, max(w, h) * 0.7f,
            intArrayOf(GLOW, 0x66500A14, Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        shinePaint.shader = LinearGradient(
            0f, 0f, titlePaint.textSize, 0f,
            intArrayOf(Color.TRANSPARENT, 0xE6FFFFFF.toInt(), Color.TRANSPARENT), null,
            Shader.TileMode.CLAMP,
        )
        streaks.forEach { it.layout(w) }
    }

    override fun onDraw(canvas: Canvas) {
        val t = time
        canvas.drawColor(Color.BLACK)
        drawGlow(canvas, t)
        drawEmbers(canvas, t)
        drawStreaks(canvas, t)

        canvas.save()
        val outro = easeInCubic(progress(t, OUTRO_AT, OUTRO_DURATION))
        if (outro > 0f) canvas.scale(1f + outro * 0.6f, 1f + outro * 0.6f, cx, cy)
        val shake = progress(t, IMPACT_AT, 0.45f)
        if (shake > 0f && shake < 1f) {
            val amplitude = 16 * dp * (1 - shake).pow(2)
            canvas.translate(amplitude * sin(t * 95f), amplitude * cos(t * 71f))
        }
        drawRings(canvas, t)
        drawSparks(canvas, t)
        drawLetters(canvas, t)
        drawShine(canvas, t)
        drawUnderline(canvas, t)
        drawCredit(canvas, t)
        canvas.restore()

        drawFlash(canvas, t)
    }

    private fun drawGlow(canvas: Canvas, t: Float) {
        val build = easeOutCubic(progress(t, 0f, 1.2f)) * 0.4f
        val firstPulse = if (t >= PULSE_AT) 0.35f * (1 - progress(t, PULSE_AT, 0.6f)) else 0f
        val impact = if (t >= IMPACT_AT) 0.6f * (1 - progress(t, IMPACT_AT, 1.2f)) else 0f
        val breathe = if (t > IMPACT_AT + 1.2f) 0.05f * sin((t - IMPACT_AT) * 3f) else 0f
        glowPaint.alpha = ((build + firstPulse + impact + breathe).coerceIn(0f, 1f) * 255).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
    }

    private fun drawEmbers(canvas: Canvas, t: Float) {
        val fade = progress(t, 0.3f, 1f)
        if (fade <= 0f) return
        val h = height.toFloat()
        for (e in embers) {
            val y = ((e.y * h - e.speed * dp * t) % h + h) % h
            val x = e.x * width + sin(t * 0.8f + e.phase) * 12 * dp
            fillPaint.color = ACCENT
            fillPaint.alpha = (e.alpha * fade * 255).toInt()
            canvas.drawCircle(x, y, e.size * dp, fillPaint)
        }
    }

    private fun drawStreaks(canvas: Canvas, t: Float) {
        for (s in streaks) {
            val p = progress(t, s.start, s.duration)
            if (p <= 0f || p >= 1f) continue
            val x = -s.length + (width + s.length) * p * p
            val thickness = s.thickness * dp
            streakPaint.shader = s.shader
            streakPaint.alpha = (sin(p * PI).toFloat() * 255).toInt()
            canvas.save()
            if (!s.leftToRight) canvas.scale(-1f, 1f, cx, cy)
            canvas.translate(x, cy + s.yFraction * height)
            canvas.drawRect(0f, -thickness / 2, s.length, thickness / 2, streakPaint)
            canvas.restore()
        }
    }

    private fun drawRings(canvas: Canvas, t: Float) {
        drawRing(canvas, t, PULSE_AT, 0.9f, ACCENT, diagonal * 0.35f, 10 * dp)
        drawRing(canvas, t, IMPACT_AT, 0.9f, Color.WHITE, diagonal * 0.6f, 18 * dp)
        drawRing(canvas, t, IMPACT_AT + 0.08f, 1.1f, ACCENT, diagonal * 0.75f, 28 * dp)
    }

    private fun drawRing(canvas: Canvas, t: Float, start: Float, duration: Float, color: Int, radius: Float, width: Float) {
        val p = progress(t, start, duration)
        if (p <= 0f || p >= 1f) return
        val e = easeOutCubic(p)
        ringPaint.color = color
        ringPaint.alpha = ((1 - p) * 255).toInt()
        ringPaint.strokeWidth = width * (1 - e) + dp
        canvas.drawCircle(cx, cy, radius * e, ringPaint)
    }

    private fun drawSparks(canvas: Canvas, t: Float) {
        val elapsed = t - IMPACT_AT
        if (elapsed <= 0f) return
        for (s in sparks) {
            val p = elapsed / s.life
            if (p >= 1f) continue
            // Fast burst that slows down, with a little gravity.
            val distance = s.speed * dp * s.life * (1 - (1 - p).pow(3)) / 3f
            val x = cx + s.origin * titleWidth + cos(s.angle) * distance
            val y = cy + s.originY * capHeight + sin(s.angle) * distance + 60 * dp * p * p
            fillPaint.color = s.color
            fillPaint.alpha = ((1 - p).pow(1.5f) * 255).toInt()
            canvas.drawCircle(x, y, s.size * dp * (1 - p * 0.5f), fillPaint)
        }
    }

    private fun drawLetters(canvas: Canvas, t: Float) {
        val impact = progress(t, IMPACT_AT, 0.8f)
        val glow = if (t >= IMPACT_AT) 10 * dp + 26 * dp * (1 - impact) else 10 * dp
        for (i in TITLE.indices) {
            val p = progress(t, LETTER_AT + i * LETTER_STAGGER, LETTER_DURATION)
            if (p <= 0f) continue
            val color = if (i >= ACCENT_FROM) ACCENT else Color.WHITE
            val x = letterX[i] + letterW[i] / 2
            val y = baseline - capHeight / 2
            if (p < 1f) {
                // Motion trail: fainter copies still flying in behind the letter.
                for (ghost in 1..2) {
                    val gp = (p - ghost * 0.08f).coerceAtLeast(0f)
                    val alpha = (0.25f / ghost * (1 - p) * 255).toInt()
                    drawLetter(canvas, i, x, y, 1f + 2.6f * (1 - easeOutCubic(gp)), color, alpha, 0f)
                }
            }
            drawLetter(canvas, i, x, y, 1f + 2.6f * (1 - easeOutCubic(p)), color, (min(1f, p * 3f) * 255).toInt(), glow)
        }
    }

    private fun drawLetter(canvas: Canvas, index: Int, x: Float, y: Float, scale: Float, color: Int, alpha: Int, glow: Float) {
        titlePaint.color = color
        titlePaint.alpha = alpha
        if (glow > 0f) titlePaint.setShadowLayer(glow, 0f, 0f, ACCENT) else titlePaint.clearShadowLayer()
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        canvas.drawText(LETTERS[index], -letterW[index] / 2, capHeight / 2, titlePaint)
        canvas.restore()
    }

    private fun drawShine(canvas: Canvas, t: Float) {
        val p = progress(t, SHINE_AT, SHINE_DURATION)
        if (p <= 0f || p >= 1f) return
        val band = titlePaint.textSize
        shineMatrix.setRotate(-20f)
        shineMatrix.postTranslate(titleLeft - band * 1.5f + (titleWidth + band * 3f) * easeInOutCubic(p), 0f)
        shinePaint.shader.setLocalMatrix(shineMatrix)
        for (i in TITLE.indices) canvas.drawText(LETTERS[i], letterX[i], baseline, shinePaint)
    }

    private fun drawUnderline(canvas: Canvas, t: Float) {
        val p = easeOutCubic(progress(t, LINE_AT, 0.6f))
        if (p <= 0f) return
        val half = titleWidth * 0.5f * p
        val top = baseline + capHeight * 0.45f
        fillPaint.color = ACCENT
        canvas.drawRect(cx - half, top, cx + half, top + 3 * dp, fillPaint)
        fillPaint.color = Color.WHITE
        fillPaint.alpha = 200
        canvas.drawRect(cx - half * 0.25f, top, cx + half * 0.25f, top + 3 * dp, fillPaint)
    }

    private fun drawCredit(canvas: Canvas, t: Float) {
        val p = progress(t, CREDIT_AT, CREDIT_DURATION)
        if (p <= 0f) return
        val e = easeOutCubic(p)
        creditPaint.letterSpacing = 0.9f - 0.55f * e
        val prefixWidth = creditPaint.measureText(CREDIT_PREFIX)
        val x = cx - (prefixWidth + creditPaint.measureText(CREDIT_NAME)) / 2
        val y = baseline + capHeight * 0.45f + 3 * dp + creditPaint.textSize * 2.2f
        creditPaint.color = 0xFFB3B3B8.toInt()
        creditPaint.alpha = (e * 255).toInt()
        canvas.drawText(CREDIT_PREFIX, x, y, creditPaint)
        creditPaint.color = Color.WHITE
        creditPaint.alpha = (e * 255).toInt()
        creditPaint.setShadowLayer(8 * dp, 0f, 0f, ACCENT)
        canvas.drawText(CREDIT_NAME, x + prefixWidth, y, creditPaint)
        creditPaint.clearShadowLayer()
    }

    private fun drawFlash(canvas: Canvas, t: Float) {
        val p = progress(t, IMPACT_AT, 0.35f)
        if (p <= 0f || p >= 1f) return
        fillPaint.color = Color.WHITE
        fillPaint.alpha = ((1 - p).pow(2) * 0.45f * 255).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
    }

    private class Streak(r: Random) {
        val yFraction = (r.nextFloat() - 0.5f) * 0.7f
        val start = 0.05f + r.nextFloat() * 1.3f
        val duration = 0.35f + r.nextFloat() * 0.4f
        private val lengthFraction = 0.25f + r.nextFloat() * 0.4f
        val thickness = 1f + r.nextFloat() * 3f
        val leftToRight = r.nextBoolean()
        private val white = r.nextFloat() < 0.35f
        var length = 0f
        var shader: Shader? = null

        fun layout(screenWidth: Int) {
            length = screenWidth * lengthFraction
            shader = LinearGradient(
                0f, 0f, length, 0f,
                intArrayOf(Color.TRANSPARENT, 0x998E1324.toInt(), if (white) Color.WHITE else ACCENT),
                floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    private class Spark(r: Random) {
        val angle = r.nextFloat() * 2 * PI.toFloat()
        val speed = 250f + r.nextFloat() * 1050f
        val origin = r.nextFloat() - 0.5f
        val originY = r.nextFloat() - 0.5f
        val size = 1.2f + r.nextFloat() * 2.8f
        val life = 0.8f + r.nextFloat() * 0.8f
        val color = when (r.nextInt(3)) {
            0 -> Color.WHITE
            1 -> ACCENT
            else -> 0xFFFF8A3D.toInt()
        }
    }

    private class Ember(r: Random) {
        val x = r.nextFloat()
        val y = r.nextFloat()
        val speed = 10f + r.nextFloat() * 30f
        val size = 1f + r.nextFloat() * 1.5f
        val alpha = 0.15f + r.nextFloat() * 0.35f
        val phase = r.nextFloat() * 6f
    }

    companion object {
        private const val TITLE = "Layan.OS"
        private val LETTERS = TITLE.map { it.toString() }
        private const val ACCENT_FROM = 5 // ".OS" in red
        private const val CREDIT_PREFIX = "CREATED BY  "
        private const val CREDIT_NAME = "DYNOZ"

        private const val ACCENT = 0xFFE0263D.toInt()
        private const val GLOW = 0xFF8E1324.toInt()

        // Timeline, in seconds.
        private const val PULSE_AT = 0.15f
        private const val LETTER_AT = 0.75f
        private const val LETTER_STAGGER = 0.09f
        private const val LETTER_DURATION = 0.4f
        private const val IMPACT_AT = LETTER_AT + 7 * LETTER_STAGGER + LETTER_DURATION
        private const val LINE_AT = 2.05f
        private const val SHINE_AT = 2.3f
        private const val SHINE_DURATION = 0.75f
        private const val CREDIT_AT = 2.45f
        private const val CREDIT_DURATION = 0.8f
        private const val OUTRO_AT = 4.1f
        private const val OUTRO_DURATION = 0.8f
        const val TOTAL = OUTRO_AT + OUTRO_DURATION

        private fun font(context: Context, id: Int, fallback: Typeface): Typeface =
            try {
                ResourcesCompat.getFont(context, id) ?: fallback
            } catch (e: Exception) {
                fallback
            }

        private fun progress(t: Float, start: Float, duration: Float) = ((t - start) / duration).coerceIn(0f, 1f)
        private fun easeOutCubic(p: Float) = 1 - (1 - p).pow(3)
        private fun easeInCubic(p: Float) = p * p * p
        private fun easeInOutCubic(p: Float) = if (p < 0.5f) 4 * p * p * p else 1 - (-2 * p + 2).pow(3) / 2
    }
}

/** Decides when the intro plays: once per boot, and when the TV wakes up. */
object IntroGate {

    private const val KEY_BOOT = "intro_boot_count"
    private var playedInProcess = false

    fun firstTimeThisBoot(context: Context): Boolean {
        val boot = if (Build.VERSION.SDK_INT >= 24) {
            try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            } catch (e: Exception) {
                -1
            }
        } else {
            -1
        }
        if (boot < 0) {
            // No boot counter: only right after the TV starts.
            val first = !playedInProcess && SystemClock.elapsedRealtime() < 5 * 60_000
            playedInProcess = true
            return first
        }
        val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_BOOT, -1) == boot) return false
        prefs.edit().putInt(KEY_BOOT, boot).apply()
        return true
    }
}
