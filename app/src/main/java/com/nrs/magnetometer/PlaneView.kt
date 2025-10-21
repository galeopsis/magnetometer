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

    // --- Параметры сетки/сцены ---
    private val cols = 12
    private val rows = 10

    // Чуть компактнее по ширине, подальше камера — сетка полностью помещается в портрете
    val worldHalfWidth = 4f     // было 6f
    val worldDepth = 22f        // было 18f
    private var camY = 3.0f     // было 3.2f
    private var camZ = 12.0f    // было 6.0f
    private var focal = 900f

    // Высоты узлов
    private val H = Array(rows + 1) { FloatArray(cols + 1) }
    private val Hnext = Array(rows + 1) { FloatArray(cols + 1) }

    // Сетка координат
    private val gridX = FloatArray(cols + 1) { i -> -worldHalfWidth + 2f * worldHalfWidth * (i.toFloat() / cols) }
    private val gridZ = FloatArray(rows + 1) { j -> worldDepth * (j.toFloat() / rows) }

    // UI/краски
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

    // Анимация покачивания
    private var time = 0f
    var externalSwayX = 0f
    var externalTiltK = 0f
    var externalBendZ = 0f
    private val anim = Anim()

    private data class P2(val x: Float, val y: Float, val zc: Float)

    // Рендеринг
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
        val dt = 1f / 60f
        stepField()
        time += dt

        val swayInner = sin(time * 0.7f) * 0.8f
        val tiltInner = sin(time * 0.5f) * 0.12f
        val bendInner = sin(time * 0.33f) * 0.25f

        anim.swayX += 0.08f * ((swayInner + externalSwayX) - anim.swayX)
        anim.tiltK += 0.08f * ((tiltInner + externalTiltK) - anim.tiltK)
        anim.bendZ += 0.08f * ((bendInner + externalBendZ) - anim.bendZ)

        invalidate()
        choreographer.postFrameCallback(this)
    }

    // Публично: добавить «всплеск»
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

    // Модель затухания/диффузии
    private fun stepField() {
        val decay = 0.98f
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Чуть меньшая «фокусная», чтобы уместить сетку в портрете
        focal = h * 0.75f   // было h * 0.9f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 1) заполнение
        for (j in 0 until rows) {
            for (i in 0 until cols) {
                val p00 = project(gridX[i],     H[j][i],     gridZ[j])     ?: continue
                val p10 = project(gridX[i + 1], H[j][i + 1], gridZ[j])     ?: continue
                val p01 = project(gridX[i],     H[j + 1][i], gridZ[j + 1]) ?: continue
                val p11 = project(gridX[i + 1], H[j + 1][i + 1], gridZ[j + 1]) ?: continue

                val avg = (H[j][i] + H[j][i + 1] + H[j + 1][i] + H[j + 1][i + 1]) * 0.25f
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
        // горизонтали
        for (j in 0..rows) {
            var move = true
            for (i in 0..cols) {
                val p = project(gridX[i], H[j][i], gridZ[j]) ?: continue
                if (move) { gridPath.moveTo(p.x, p.y); move = false } else gridPath.lineTo(p.x, p.y)
            }
        }
        // вертикали
        for (i in 0..cols) {
            var move = true
            for (j in 0..rows) {
                val p = project(gridX[i], H[j][i], gridZ[j]) ?: continue
                if (move) { gridPath.moveTo(p.x, p.y); move = false } else gridPath.lineTo(p.x, p.y)
            }
        }
        canvas.drawPath(gridPath, paintGrid)

        // 3) подсветка «пиков»
        for (j in 1 until rows) {
            for (i in 1 until cols) {
                val h = H[j][i]
                if (h < 0.25f) continue
                val p = project(gridX[i], h, gridZ[j]) ?: continue
                val r = max(2f, 6f - (p.zc * 0.05f))
                canvas.drawCircle(p.x, p.y, r, paintGlow)
            }
        }
    }

    // Простейшая перспектива + покачивание
    private fun project(x: Float, y: Float, z: Float): P2? {
        val x2 = x + anim.swayX
        val y2 = y + anim.tiltK * x + anim.bendZ * ((z - worldDepth * 0.5f) / worldDepth)

        val zc = z + camZ
        if (zc <= 0.01f) return null

        val cx = width * 0.5f
        // Чуть ниже центр, чтобы нижняя кромка сетки не упиралась в экран
        val cy = height * 0.5f + 10f   // было +30f

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
