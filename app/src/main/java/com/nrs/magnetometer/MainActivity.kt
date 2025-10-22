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
import kotlin.math.atan2
import kotlin.math.max
import android.content.pm.ActivityInfo

class MainActivity : Activity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    private var accelerometer: Sensor? = null   // <-- добавили

    private lateinit var planeView: PlaneView
    private lateinit var tvMag: TextView
    private lateinit var tvDelta: TextView

    // сглаженные значения магнитного поля (μT)
    private var magX = 0f
    private var magY = 0f
    private var magZ = 0f

    // акселерометр (гравитация, м/с^2), LP-фильтр
    private var ax = 0f
    private var ay = 0f
    private var az = 0f
    private val accAlpha = 0.2f

    // углы (рад)
    private var roll = 0f    // крен: +вправо/−влево
    private var pitch = 0f   // тангаж: +нос вверх/−вниз

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

    // --- параметры устойчивой «башни» ---
    private val sustainK = 0.02f
    private val sustainAmpMax = 0.12f
    private val sustainRadiusBase = 1.2f
    private val sustainRadiusMin = 0.8f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)

        planeView = findViewById(R.id.planeView)
        tvMag = findViewById(R.id.tvMag)
        tvDelta = findViewById(R.id.tvDelta)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)  // <-- добавили
    }

    override fun onResume() {
        super.onResume()
        magnetometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        accelerometer?.also {
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
        when (event.sensor.type) {

            // --- АКСЕЛЕРОМЕТР: считаем roll/pitch и кормим плоскость ---
            Sensor.TYPE_ACCELEROMETER -> {
                val rx = event.values[0]
                val ry = event.values[1]
                val rz = event.values[2]

                // LP-фильтр по гравитации
                ax += accAlpha * (rx - ax)
                ay += accAlpha * (ry - ay)
                az += accAlpha * (rz - az)

                // углы из аксела (без азимута): roll ~ atan2(ay, az), pitch ~ atan2(-ax, sqrt(ay^2+az^2))
                val rollRad = atan2(ay.toDouble(), az.toDouble()).toFloat()
                val pitchRad = atan2(-ax.toDouble(), sqrt((ay*ay + az*az).toDouble())).toFloat()

                // лёгкое сглаживание углов (EMA поверх уже сглаженной гравитации)
                val angleAlpha = 0.2f
                roll += angleAlpha * (rollRad - roll)
                pitch += angleAlpha * (pitchRad - pitch)

                // МАППИНГ → плоскость (не сильно)
                // ограничим углы ~±15° (0.26 рад), и масштабируем
                val rollClamped = roll.coerceIn(-0.26f, 0.26f)
                val pitchClamped = pitch.coerceIn(-0.26f, 0.26f)

                // swayX — сдвиг влево/вправо по экрану
                planeView.externalSwayX = (rollClamped * 0.9f)    // 0.9 — мягкое влияние

                // tiltK — наклон по X (визуальный «скат»)
                planeView.externalTiltK = (pitchClamped * 0.5f)   // чуть меньше, чтобы не «заваливать»

                // bendZ — лёгкий «прогиб» по мере наклона (от величины уголка)
                val tiltMag = kotlin.math.abs(rollClamped) + kotlin.math.abs(pitchClamped)
                planeView.externalBendZ = (tiltMag * 0.35f).coerceIn(0f, 0.35f)
            }

            // --- МАГНИТОМЕТР: как было (всплески/высота) ---
            Sensor.TYPE_MAGNETIC_FIELD -> {
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

                // baseline «заморозка» при сильном поле
                val predictedDelta = mag - baseline
                val absPred = kotlin.math.abs(predictedDelta)
                val dynamicAlpha = when {
                    absPred > spikeThreshold * 1.2f -> 0.0f
                    absPred > spikeThreshold * 0.7f -> 0.001f
                    else -> baseAlpha
                }
                baseline += dynamicAlpha * (mag - baseline)

                val delta = mag - baseline
                tvMag.text = "Mag: ${"%.1f".format(mag)} μT"
                tvDelta.text = "Δ: ${"%.1f".format(delta)} μT"

                // позиция «башни» по магнитному полю
                val normX = (magX / 60f).coerceIn(-1f, 1f)
                val normZ = (mag / 100f).coerceIn(0f, 1f)
                val worldX = planeView.worldHalfWidth * (flipX * normX)
                val worldZ = planeView.worldDepth * normZ

                // устойчивая подпитка плато
                val over = (mag - baseline).coerceAtLeast(0f)
                if (over > 0f) {
                    val sustainAmp = (over * sustainK).coerceIn(0f, sustainAmpMax)
                    val sustainRadius = (sustainRadiusBase - over * 0.01f).coerceAtLeast(sustainRadiusMin)
                    planeView.addPulse(worldX, worldZ, sustainAmp, sustainRadius)
                }

                // разовый всплеск при резком росте
                if (abs(delta) > spikeThreshold) {
                    val d = abs(delta)
                    val amp = (1.2f + d / 6f).coerceIn(1.2f, 4.0f)
                    val radius = (0.9f + d / 22f).coerceIn(0.8f, 1.8f)
                    planeView.addPulse(worldX, worldZ, amp, radius)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // игнорируем
    }
}
