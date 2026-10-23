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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The "LayanTv" intro: a soft light rises out of the dark, a hairline draws
 * across the screen, the wordmark settles in letter by letter while its
 * spacing closes, a gentle sheen passes over it, the credit fades in, and the
 * whole scene dissolves into the home screen.
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
            alpha = 1f - easeInOut(progress(value, OUTRO_AT, OUTRO_DURATION))
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private var startNanos = 0L
    private var running = false
    private var finished = false

    private val romanPaint = textPaint(font(context, R.font.cormorant_medium, Typeface.SERIF))
    private val italicPaint = textPaint(font(context, R.font.cormorant_medium_italic, Typeface.SERIF))
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val creditPaint = textPaint(font(context, R.font.jost_regular, Typeface.SANS_SERIF))
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightPaint = Paint()
    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenMatrix = Matrix()
    private var sheenShader: Shader? = null

    private val letterWidth = FloatArray(TITLE.length)
    private var titleWidth = 0f
    private var baseline = 0f
    private var capHeight = 0f
    private var cx = 0f
    private var cy = 0f

    private val dust = List(36) { Mote(Random(it * 31 + 7)) }

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
        animate().alpha(0f).setDuration(300).withEndAction { finish() }.start()
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
        cy = h * 0.47f

        romanPaint.textSize = 100f
        italicPaint.textSize = 100f
        val measured = (TITLE.indices).sumOf { paintFor(it).measureText(LETTERS[it]).toDouble() }.toFloat()
        val size = min(100f * w * 0.42f / measured, h * 0.22f)
        romanPaint.textSize = size
        italicPaint.textSize = size
        sheenPaint.textSize = size
        for (i in TITLE.indices) letterWidth[i] = paintFor(i).measureText(LETTERS[i])
        titleWidth = letterWidth.sum()

        val bounds = Rect()
        romanPaint.getTextBounds("L", 0, 1, bounds)
        capHeight = bounds.height().toFloat()
        baseline = cy + capHeight / 2

        // Ivory at the top of the letters warming to champagne at the bottom.
        val fill = LinearGradient(0f, -capHeight, 0f, capHeight * 0.3f, IVORY, CHAMPAGNE, Shader.TileMode.CLAMP)
        romanPaint.shader = fill
        italicPaint.shader = fill
        sheenShader = LinearGradient(
            0f, 0f, size * 0.9f, 0f,
            intArrayOf(Color.TRANSPARENT, 0x8CFFFFFF.toInt(), Color.TRANSPARENT), null,
            Shader.TileMode.CLAMP,
        )
        sheenPaint.shader = sheenShader
        romanPaint.setShadowLayer(22 * dp, 0f, 0f, GLOW)
        italicPaint.setShadowLayer(22 * dp, 0f, 0f, GLOW)

        creditPaint.textSize = max(size * 0.12f, 11 * dp)
        creditPaint.color = MUTED

        lightPaint.shader = RadialGradient(
            cx, cy, max(w, h) * 0.6f,
            intArrayOf(LIGHT, 0x14E9DFC8, Color.TRANSPARENT), floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = time
        canvas.drawColor(BACKGROUND)

        canvas.save()
        val outro = easeInOut(progress(t, OUTRO_AT, OUTRO_DURATION))
        if (outro > 0f) canvas.scale(1f + outro * 0.04f, 1f + outro * 0.04f, cx, cy)
        drawLight(canvas, t)
        drawDust(canvas, t)
        drawHairline(canvas, t)
        drawTitle(canvas, t)
        drawSheen(canvas, t)
        drawCredit(canvas, t)
        canvas.restore()
    }

    private fun drawLight(canvas: Canvas, t: Float) {
        val rise = easeInOut(progress(t, 0f, 1.6f))
        val breathe = 0.06f * sin(t * 1.6f)
        lightPaint.alpha = ((rise * (0.85f + breathe)).coerceIn(0f, 1f) * 255).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), lightPaint)
    }

    private fun drawDust(canvas: Canvas, t: Float) {
        val fade = easeInOut(progress(t, 0.6f, 1.6f))
        if (fade <= 0f) return
        val h = height.toFloat()
        for (m in dust) {
            val y = ((m.y * h - m.speed * dp * t) % h + h) % h
            val x = m.x * width + sin(t * 0.5f + m.phase) * 10 * dp
            val twinkle = 0.6f + 0.4f * sin(t * 1.3f + m.phase)
            dustPaint.color = IVORY
            dustPaint.alpha = (m.alpha * twinkle * fade * 255).toInt()
            canvas.drawCircle(x, y, m.size * dp, dustPaint)
        }
    }

    private fun drawHairline(canvas: Canvas, t: Float) {
        val draw = easeOutQuint(progress(t, LINE_AT, 1.2f))
        if (draw <= 0f) return
        val half = titleWidth * 0.75f * draw
        val y = baseline + capHeight * 0.42f
        linePaint.shader = LinearGradient(
            cx - half, 0f, cx + half, 0f,
            intArrayOf(Color.TRANSPARENT, CHAMPAGNE, Color.TRANSPARENT), null,
            Shader.TileMode.CLAMP,
        )
        linePaint.alpha = (0.7f * 255).toInt()
        canvas.drawRect(cx - half, y, cx + half, y + max(1f, dp * 0.75f), linePaint)
    }

    private fun drawTitle(canvas: Canvas, t: Float) {
        // The letters arrive spread apart and draw together as they settle.
        val close = easeOutQuint(progress(t, LETTER_AT, 2.2f))
        val extra = titleWidth * 0.06f * (1 - close)
        val totalWidth = titleWidth + extra * (TITLE.length - 1)
        var x = cx - totalWidth / 2
        for (i in TITLE.indices) {
            val p = easeOutCubic(progress(t, LETTER_AT + i * LETTER_STAGGER, LETTER_DURATION))
            if (p > 0f) {
                val paint = paintFor(i)
                paint.alpha = (p * 255).toInt()
                canvas.save()
                canvas.translate(x, baseline + 10 * dp * (1 - p))
                canvas.drawText(LETTERS[i], 0f, 0f, paint)
                canvas.restore()
            }
            x += letterWidth[i] + extra
        }
    }

    private fun drawSheen(canvas: Canvas, t: Float) {
        val p = progress(t, SHEEN_AT, SHEEN_DURATION)
        if (p <= 0f || p >= 1f) return
        val band = romanPaint.textSize
        val left = cx - titleWidth / 2
        sheenMatrix.setRotate(-18f)
        sheenMatrix.postTranslate(left - band * 1.5f + (titleWidth + band * 3f) * easeInOut(p), 0f)
        sheenShader?.setLocalMatrix(sheenMatrix)
        var x = left
        for (i in TITLE.indices) {
            sheenPaint.typeface = paintFor(i).typeface
            canvas.drawText(LETTERS[i], x, baseline, sheenPaint)
            x += letterWidth[i]
        }
    }

    private fun drawCredit(canvas: Canvas, t: Float) {
        val p = easeOutCubic(progress(t, CREDIT_AT, CREDIT_DURATION))
        if (p <= 0f) return
        creditPaint.letterSpacing = 0.55f - 0.15f * p
        creditPaint.alpha = (p * 0.75f * 255).toInt()
        val width = creditPaint.measureText(CREDIT)
        val y = baseline + capHeight * 0.42f + creditPaint.textSize * 3.2f
        canvas.drawText(CREDIT, cx - width / 2, y, creditPaint)
    }

    private fun paintFor(index: Int) = if (index >= ITALIC_FROM) italicPaint else romanPaint

    private class Mote(r: Random) {
        val x = r.nextFloat()
        val y = r.nextFloat()
        val speed = 4f + r.nextFloat() * 10f
        val size = 0.6f + r.nextFloat() * 1.2f
        val alpha = 0.08f + r.nextFloat() * 0.2f
        val phase = r.nextFloat() * 6f
    }

    companion object {
        private const val TITLE = "LayanTv"
        private val LETTERS = TITLE.map { it.toString() }
        private const val ITALIC_FROM = 5 // "Tv" in italic
        private const val CREDIT = "CREATED BY DYNOZ"

        private const val BACKGROUND = 0xFF07080A.toInt()
        private const val IVORY = 0xFFF7F3EA.toInt()
        private const val CHAMPAGNE = 0xFFD6C49F.toInt()
        private const val MUTED = 0xFFB9B2A4.toInt()
        private const val GLOW = 0x40E9DCC0
        private const val LIGHT = 0x40CFC3A8

        // Timeline, in seconds.
        private const val LINE_AT = 0.4f
        private const val LETTER_AT = 0.9f
        private const val LETTER_STAGGER = 0.08f
        private const val LETTER_DURATION = 1.1f
        private const val SHEEN_AT = 2.6f
        private const val SHEEN_DURATION = 1.1f
        private const val CREDIT_AT = 2.9f
        private const val CREDIT_DURATION = 1.0f
        private const val OUTRO_AT = 4.4f
        private const val OUTRO_DURATION = 0.9f
        const val TOTAL = OUTRO_AT + OUTRO_DURATION

        private fun textPaint(face: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face }

        private fun font(context: Context, id: Int, fallback: Typeface): Typeface =
            try {
                ResourcesCompat.getFont(context, id) ?: fallback
            } catch (e: Exception) {
                fallback
            }

        private fun progress(t: Float, start: Float, duration: Float) = ((t - start) / duration).coerceIn(0f, 1f)
        private fun easeOutCubic(p: Float) = 1 - (1 - p).pow(3)
        private fun easeOutQuint(p: Float) = 1 - (1 - p).pow(5)
        private fun easeInOut(p: Float) = if (p < 0.5f) 4 * p * p * p else 1 - (-2 * p + 2).pow(3) / 2
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
