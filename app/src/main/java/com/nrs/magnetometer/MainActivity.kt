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
import android.content.pm.ActivityInfo   // <-- добавили

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

        if (!filterInited) {
            magX = rx; magY = ry; magZ = rz
            filterInited = true
        } else {
            magX += lpAlpha * (rx - magX)
            magY += lpAlpha * (ry - magY)
            magZ += lpAlpha * (rz - magZ)
        }

        val mag = sqrt(magX*magX + magY*magY + magZ*magZ)

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

        val predictedDelta = mag - baseline
        val dynamicAlpha = if (kotlin.math.abs(predictedDelta) > spikeThreshold) 0.05f else baseAlpha
        baseline += dynamicAlpha * (mag - baseline)

        val delta = mag - baseline
        tvMag.text = "Mag: ${"%.1f".format(mag)} μT"
        tvDelta.text = "Δ: ${"%.1f".format(delta)} μT"

        // Визуализация (покачивания)
        planeView.externalSwayX = (magX * 0.02f).coerceIn(-1.2f, 1.2f)
        planeView.externalTiltK = (magY * 0.006f).coerceIn(-0.25f, 0.25f)
        planeView.externalBendZ = (magZ * 0.003f).coerceIn(-0.35f, 0.35f)

        // Всплески
        if (kotlin.math.abs(delta) > spikeThreshold) {
            val normX = (magX / 60f).coerceIn(-1f, 1f)
            val normZ = (mag / 100f).coerceIn(0f, 1f)

            val worldX = planeView.worldHalfWidth * (flipX * normX)
            val worldZ = planeView.worldDepth * normZ

            val d = kotlin.math.abs(delta)
            val amp = (1.2f + d / 6f).coerceIn(1.2f, 4.0f)
            val radius = (0.9f + d / 22f).coerceIn(0.9f, 1.8f)

            planeView.addPulse(worldX, worldZ, amp, radius)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // игнорируем
    }
}
