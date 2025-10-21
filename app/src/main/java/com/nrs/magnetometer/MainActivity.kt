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
    private val lpAlpha = 0.10f   // сглаживание показаний
    private val baseAlpha = 0.005f // медленный дрейф базы (долгосрочная средняя)

    // порог «всплеска» (можно подбирать)
    private var spikeThreshold = 6f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        // Низкочастотный фильтр (сгладим шум)
        magX += lpAlpha * (event.values[0] - magX)
        magY += lpAlpha * (event.values[1] - magY)
        magZ += lpAlpha * (event.values[2] - magZ)

        val mag = sqrt(magX*magX + magY*magY + magZ*magZ) // модуль в μT

        if (!baselineReady) {
            baseline = mag
            baselineReady = true
        } else {
            // медленное обновление базового уровня
            baseline += baseAlpha * (mag - baseline)
        }

        val delta = mag - baseline
        tvMag.text = "Mag: ${"%.1f".format(mag)} μT"
        tvDelta.text = "Δ: ${"%.1f".format(delta)} μT"

        // Связь c визуализацией:
        // 1) Покачивание плоскости (сдвиг/наклон) — от компонент поля
        planeView.externalSwayX = (magX * 0.02f).coerceIn(-1.2f, 1.2f)  // смещение влево/вправо
        planeView.externalTiltK = (magY * 0.006f).coerceIn(-0.25f, 0.25f) // наклон по X
        planeView.externalBendZ = (magZ * 0.003f).coerceIn(-0.35f, 0.35f) // «прогиб» вдоль Z

        // 2) Детектор «всплесков» — быстрые изменения выше порога
        if (abs(delta) > spikeThreshold) {
            // позиционируем всплеск: направление по (X,Y) → угол, а Z — от величины
            // Для простоты: X влияет на X плоскости, Z — на глубину
            val normX = (magX / 60f).coerceIn(-1f, 1f)
            val normZ = (mag / 100f).coerceIn(0f, 1f)
            val worldX = planeView.worldHalfWidth * normX
            val worldZ = planeView.worldDepth * normZ
            val amp = (abs(delta) / 10f).coerceIn(0.8f, 3.0f)
            val radius = (1.2f + abs(delta) / 15f).coerceIn(1.0f, 2.5f)
            planeView.addPulse(worldX, worldZ, amp, radius)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // игнорируем
    }
}
