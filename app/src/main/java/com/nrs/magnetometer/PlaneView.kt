package com.nrs.magnetometer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.*

class PlaneView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    private val cols = 12
    private val rows = 10

    val worldHalfWidth = 4f
    val worldDepth = 22f
    private var camY = 3.0f
    private var camZ = 12.0f
    private var focal = 900f

    // Физическое поле и «визуальная» копия
    private val H = Array(rows + 1) { FloatArray(cols + 1) }
    private val Hnext = Array(rows + 1) { FloatArray(cols + 1) }
    private val Hs = Array(rows + 1) { FloatArray(cols + 1) }

    private val gridX = FloatArray(cols + 1) { i -> -worldHalfWidth + 2f * worldHalfWidth * (i.toFloat() / cols) }
    private val gridZ = FloatArray(rows + 1) { j -> worldDepth * (j.toFloat() / rows) }

    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 220, 220, 220)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 0, 180, 255)
        setShadowLayer(20f, 0f, 0f, Color.argb(230, 0, 160, 255))
    }

    // внешние сигналы
    var externalSwayX = 0f
    var externalTiltK = 0f
    var externalBendZ = 0f

    // плавная интерполяция к внешним сигналам
    private val anim = Anim()
    private val interpAlpha = 0.12f   // скорость подстройки к внешним

    // физика
    private val decay = 0.98f

    // стабилизация визуального поля
    private val visEps = 0.03f
    private val visAlphaUp = 0.25f
    private val visAlphaDown = 0.08f
    private val visQuant = 0.01f
    private val visFloor = 0.0f

    private data class P2(val x: Float, val y: Float, val zc: Float)
    private val path = Path()
    private val choreographer = Choreographer.getInstance()
    private var running = false

    fun start() {
        if (!running) {
            running = true
            choreographer.postFrameCallback(this)
        }
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        stepField()
        smoothVisual()

        // БЕЗ внутренней синусоиды: только следуем за external*
        anim.swayX += interpAlpha * (externalSwayX - anim.swayX)
        anim.tiltK += interpAlpha * (externalTiltK - anim.tiltK)
        anim.bendZ += interpAlpha * (externalBendZ - anim.bendZ)

        invalidate()
        choreographer.postFrameCallback(this)
    }

    fun addPulse(worldX: Float, worldZ: Float, amp: Float = 1.5f, radius: Float = 2.0f) {
        for (j in 0..rows) {
            for (i in 0..cols) {
                val dx = gridX[i] - worldX
                val dz = gridZ[j] - worldZ
                val r2 = dx*dx + dz*dz
                val gain = exp(-r2 / (radius*radius))
                H[j][i] += amp * gain
            }
        }
    }

    private fun stepField() {
        for (j in 0..rows) {
            val row = H[j]
            val rowU = H[max(0, j - 1)]
            val rowD = H[min(rows, j + 1)]
            val out = Hnext[j]
            for (i in 0..cols) {
                val center = row[i]
                val l = row[max(0, i - 1)]
                val r = row[min(cols, i + 1)]
                val u = rowU[i]
                val d = rowD[i]
                val lap = (l + r + u + d - 4f * center) * 0.25f
                out[i] = (center + lap * 0.65f) * decay
            }
        }
        for (j in 0..rows) System.arraycopy(Hnext[j], 0, H[j], 0, cols + 1)
    }

    private fun smoothVisual() {
        for (j in 0..rows) {
            val rowH = H[j]
            val rowS = Hs[j]
            for (i in 0..cols) {
                val h = rowH[i]
                var s = rowS[i]
                val diff = h - s

                if (abs(diff) > visEps) {
                    val a = if (diff > 0f) visAlphaUp else visAlphaDown
                    s += a * diff
                }
                s = (s / visQuant).roundToInt() * visQuant
                if (s < visFloor) s = 0f
                rowS[i] = s
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        focal = h * 0.75f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1) заполнение по Hs
        for (j in 0 until rows) {
            for (i in 0 until cols) {
                val p00 = project(gridX[i],     Hs[j][i],     gridZ[j])     ?: continue
                val p10 = project(gridX[i + 1], Hs[j][i + 1], gridZ[j])     ?: continue
                val p01 = project(gridX[i],     Hs[j + 1][i], gridZ[j + 1]) ?: continue
                val p11 = project(gridX[i + 1], Hs[j + 1][i + 1], gridZ[j + 1]) ?: continue

                val avg = (Hs[j][i] + Hs[j][i + 1] + Hs[j + 1][i] + Hs[j + 1][i + 1]) * 0.25f
                val bright = (0.5f + avg * 0.7f).coerceIn(0f, 1f)
                paintFill.color = Color.argb(
                    (0.08f * 255).toInt(),
                    (40 + bright * 160).toInt(),
                    (40 + bright * 160).toInt(),
                    (60 + bright * 180).toInt()
                )

                path.reset()
                path.moveTo(p00.x, p00.y)
                path.lineTo(p10.x, p10.y)
                path.lineTo(p11.x, p11.y)
                path.lineTo(p01.x, p01.y)
                path.close()
                canvas.drawPath(path, paintFill)
            }
        }

        // 2) сетка
        val gridPath = Path()
        for (j in 0..rows) {
            var move = true
            for (i in 0..cols) {
                val p = project(gridX[i], Hs[j][i], gridZ[j]) ?: continue
                if (move) { gridPath.moveTo(p.x, p.y); move = false } else gridPath.lineTo(p.x, p.y)
            }
        }
        for (i in 0..cols) {
            var move = true
            for (j in 0..rows) {
                val p = project(gridX[i], Hs[j][i], gridZ[j]) ?: continue
                if (move) { gridPath.moveTo(p.x, p.y); move = false } else gridPath.lineTo(p.x, p.y)
            }
        }
        canvas.drawPath(gridPath, paintGrid)

        // 3) подсветки по Hs
        val h0 = 0.15f
        val h1 = 0.50f
        for (j in 1 until rows) {
            for (i in 1 until cols) {
                val h = Hs[j][i]
                val a = ((h - h0) / (h1 - h0)).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val p = project(gridX[i], h, gridZ[j]) ?: continue
                val r = max(2f, 6f - (p.zc * 0.05f))
                paintGlow.alpha = (a * 220f).toInt()
                canvas.drawCircle(p.x, p.y, r, paintGlow)
            }
        }
    }

    private fun project(x: Float, y: Float, z: Float): P2? {
        val x2 = x + anim.swayX
        val y2 = y + anim.tiltK * x + anim.bendZ * ((z - worldDepth * 0.5f) / worldDepth)

        val zc = z + camZ
        if (zc <= 0.01f) return null

        val cx = width * 0.5f
        val cy = height * 0.5f + 10f
        val sx = cx + (focal * x2) / zc
        val sy = cy - (focal * (y2 - camY)) / zc
        return P2(sx, sy, zc)
    }

    private class Anim {
        var swayX = 0f
        var tiltK = 0f
        var bendZ = 0f
    }
}
