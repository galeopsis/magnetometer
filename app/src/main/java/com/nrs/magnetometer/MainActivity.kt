package com.nrs.magnetometer

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sqrt
import android.content.pm.ActivityInfo

class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null

    private lateinit var planeView: PlaneView
    private lateinit var tvMag: TextView
    private lateinit var tvDelta: TextView

    // сглаженные значения магнитного поля (μT)
    private var magX = 0f
    private var magY = 0f
    private var magZ = 0f

    // базовый уровень + детектор всплесков
    private var baseline = 0f
    private var baselineReady = false

    // простые фильтры
    private val lpAlpha = 0.10f
    private val baseAlpha = 0.005f

    // порог «всплеска»
    private var spikeThreshold = 6f

    // --- старт/прогрев ---
    private var warmupSamples = 20
    private var warmupLeft = warmupSamples
    private var warmupSum = 0f
    private var filterInited = false

    // --- антиспам и отражение X ---
    private var lastPulseTime = 0L
    private val minPulseIntervalMs = 350L
    private var flipX = -1f

    // --- параметры устойчивой «башни» (можно подбирать) ---
    private val sustainK = 0.02f        // коэффициент перевода избытка μT в амплитуду за кадр
    private val sustainAmpMax = 0.12f   // ограничение амплитуды подпитки за кадр (плато ≈ amp/0.02)
    private val sustainRadiusBase = 1.2f
    private val sustainRadiusMin = 0.8f // при сильном поле радиус сужаем, башня выше

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Фиксируем портретную ориентацию
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setContentView(R.layout.activity_main)

        planeView = findViewById(R.id.planeView)
        tvMag = findViewById(R.id.tvMag)
        tvDelta = findViewById(R.id.tvDelta)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    override fun onResume() {
        super.onResume()
        magnetometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        planeView.start()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        planeView.stop()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return

        val rx = event.values[0]
        val ry = event.values[1]
        val rz = event.values[2]

        // инициализация фильтра первым реальным значением
        if (!filterInited) {
            magX = rx; magY = ry; magZ = rz
            filterInited = true
        } else {
            magX += lpAlpha * (rx - magX)
            magY += lpAlpha * (ry - magY)
            magZ += lpAlpha * (rz - magZ)
        }

        val mag = sqrt(magX*magX + magY*magY + magZ*magZ)

        // прогрев
        if (warmupLeft > 0) {
            warmupSum += mag
            warmupLeft--
            if (warmupLeft == 0) {
                baseline = warmupSum / warmupSamples
                baselineReady = true
            }
            tvMag.text = "Mag: ${"%.1f".format(mag)} μT"
            tvDelta.text = "Δ: -- μT"
            return
        }

        // динамика baseline: если поле заметно выше фона — почти «замораживаем» базу
        val predictedDelta = mag - baseline
        val absPred = kotlin.math.abs(predictedDelta)
        val dynamicAlpha = when {
            absPred > spikeThreshold * 1.2f -> 0.0f     // сильное поле — не трогаем базу
            absPred > spikeThreshold * 0.7f -> 0.001f   // умеренно сильное — очень медленно
            else -> baseAlpha                              // иначе обычный дрейф базы
        }
        baseline += dynamicAlpha * (mag - baseline)

        val delta = mag - baseline
        tvMag.text = "Mag: ${"%.1f".format(mag)} μT"
        tvDelta.text = "Δ: ${"%.1f".format(delta)} μT"

        // покачивания
        planeView.externalSwayX = (magX * 0.02f).coerceIn(-1.2f, 1.2f)
        planeView.externalTiltK = (magY * 0.006f).coerceIn(-0.25f, 0.25f)
        planeView.externalBendZ = (magZ * 0.003f).coerceIn(-0.35f, 0.35f)

        // позиционирование всплеска по X/Z
        val normX = (magX / 60f).coerceIn(-1f, 1f)
        val normZ = (mag / 100f).coerceIn(0f, 1f)
        val worldX = planeView.worldHalfWidth * (flipX * normX)
        val worldZ = planeView.worldDepth * normZ

        // --- 1) Устойчивая «башня» пока магнит рядом ---
        // Берём избыток над базой (только положительный), переводим в амплитуду за кадр.
        val over = (mag - baseline).coerceAtLeast(0f)
        if (over > 0f) {
            // амплитуда подпитки за кадр — ограничена
            val sustainAmp = (over * sustainK).coerceIn(0f, sustainAmpMax)
            // чем сильнее поле — тем уже радиус (выше башня)
            val sustainRadius = (sustainRadiusBase - over * 0.01f).coerceAtLeast(sustainRadiusMin)
            planeView.addPulse(worldX, worldZ, sustainAmp, sustainRadius)
        }

        // --- 2) (Опционально) одиночный «всплеск» при резком росте ---
        if (abs(delta) > spikeThreshold) {
            val d = abs(delta)
            val amp = (1.2f + d / 6f).coerceIn(1.2f, 4.0f)
            val radius = (0.9f + d / 22f).coerceIn(0.8f, 1.8f)
            planeView.addPulse(worldX, worldZ, amp, radius)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // игнорируем
    }
}
