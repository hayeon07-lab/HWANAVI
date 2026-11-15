
package com.example.daesinhwanaecar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Pleos / Android Automotive 차량 속도 모니터.
 *
 * 화면이 가려져도 속도 모니터링을 유지하도록
 * 사용자 동의로 시작하는 Foreground Service.
 *
 * 실제 마이크·스마트워치·자동 오디오 재생은
 * 이 클래스에서 연결된 것으로 취급하지 않는다.
 */
class PleosVehicleMonitorService : Service() {

    companion object {
        const val ACTION_STOP =
            "com.example.daesinhwanaecar.STOP_VEHICLE_MONITOR"

        const val PREFS_NAME =
            "hwanaebi_vehicle_monitor"

        private const val TAG = "HwanaebiVehicle"
        private const val CHANNEL_ID =
            "hwanaebi_vehicle_monitor"

        private const val NOTIFICATION_ID = 1107

        private const val SPEED_PERMISSION =
            "android.car.permission.CAR_SPEED"

        private const val SPEED_PROPERTY =
            VehiclePropertyIds.PERF_VEHICLE_SPEED
    }

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private val detector = VehicleDeceleration()

    private val prefs by lazy {
        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    private val notifications by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
    }

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    private var subscribed = false
    private var active = false

    private var lastSampleAtMs = -1L
    private var lastDisplayAtMs = -1L
    private var brakes = 0

    private val speedListener =
        object : CarPropertyManager.CarPropertyEventCallback {

            override fun onChangeEvent(
                value: CarPropertyValue<*>
            ) {
                if (
                    !active ||
                    value.propertyId != SPEED_PROPERTY
                ) return

                if (
                    value.status !=
                    CarPropertyValue.STATUS_AVAILABLE
                ) {
                    markUnavailable(
                        "차량 속도 데이터 사용 불가"
                    )
                    return
                }

                val speed =
                    (value.value as? Number)?.toFloat()
                        ?: run {
                            markUnavailable("속도 형식 오류")
                            return
                        }

                onSpeed(speed)
            }

            override fun onErrorEvent(
                propertyId: Int,
                areaId: Int
            ) {
                if (propertyId == SPEED_PROPERTY) {
                    markUnavailable(
                        "차량 속도 수신 오류"
                    )
                }
            }
        }

    private val watchdog = object : Runnable {

        override fun run() {
            if (!active) return

            val now = SystemClock.elapsedRealtime()

            if (
                lastSampleAtMs < 0L ||
                now - lastSampleAtMs > 3_000L
            ) {
                markUnavailable(
                    "차량 신호 대기/끊김"
                )
            }

            mainHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "화내비 차량 신호 모니터",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        brakes = prefs.getInt("hardBrakes", 0)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (active) return START_NOT_STICKY

        val automotive =
            packageManager.hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE
            )

        val hasPermission =
            checkSelfPermission(SPEED_PERMISSION) ==
                    PackageManager.PERMISSION_GRANTED

        if (!automotive || !hasPermission) {

            prefs.edit()
                .putBoolean("connected", false)
                .putString(
                    "status",
                    "차량 환경 또는 속도 권한 없음"
                )
                .apply()

            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val initialNotification =
                notification("차량 속도 연결 중")

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo
                        .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification
                )
            }

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Foreground service cannot start",
                error
            )

            stopSelf()
            return START_NOT_STICKY
        }

        active = true

        prefs.edit()
            .putBoolean("connected", false)
            .putString("status", "연결 중")
            .apply()

        mainHandler.post(watchdog)
        connectVehicle()

        return START_NOT_STICKY
    }

    private fun connectVehicle() {

        try {
            car = Car.createCar(
                this,
                mainHandler,
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT
            ) { connectedCar, ready ->

                if (!active) return@createCar

                if (ready) {
                    subscribe(connectedCar)
                } else {
                    subscribed = false
                    propertyManager = null

                    markUnavailable(
                        "차량 서비스 연결 해제"
                    )
                }
            }

            car?.takeIf {
                it.isConnected
            }?.let(::subscribe)

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Car service connection failed",
                error
            )

            markUnavailable(
                "차량 서비스 연결 실패"
            )
        }
    }

    private fun subscribe(connectedCar: Car) {

        if (subscribed || !active) return

        try {
            val manager =
                connectedCar.getCarManager(
                    Car.PROPERTY_SERVICE
                ) as? CarPropertyManager
                    ?: error("CarPropertyManager 없음")

            propertyManager = manager

            @Suppress("DEPRECATION")
            val registered = manager.registerCallback(
                speedListener,
                SPEED_PROPERTY,
                5f
            )

            check(registered) {
                "속도 콜백 등록 실패"
            }

            subscribed = true

            runCatching {
                onSpeed(
                    manager.getFloatProperty(
                        SPEED_PROPERTY,
                        0
                    )
                )
            }.onFailure {
                Log.w(
                    TAG,
                    "초기 속도값 대기",
                    it
                )
            }

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Speed subscription failed",
                error
            )

            subscribed = false

            markUnavailable(
                "속도 권한/프로퍼티 확인 필요"
            )
        }
    }

    private fun onSpeed(speedMps: Float) {

        if (!active) return

        val now = SystemClock.elapsedRealtime()

        val value = detector.accept(
            speedMps,
            now
        ) ?: run {
            markUnavailable(
                "비정상 속도 데이터"
            )
            return
        }

        lastSampleAtMs = now

        if (value.hardBrake) {
            brakes++

            Log.i(
                TAG,
                "HARD_BRAKE detected: " +
                        "${value.accelerationMps2} m/s²"
            )
        }

        val shouldUpdate =
            value.hardBrake ||
                    lastDisplayAtMs < 0L ||
                    now - lastDisplayAtMs >= 1_000L

        if (!shouldUpdate) return

        lastDisplayAtMs = now

        val description = String.format(
            Locale.US,
            "%.1f km/h · 급제동 %d회",
            value.speedKmh,
            brakes
        )

        prefs.edit()
            .putBoolean("connected", true)
            .putFloat(
                "speedKmh",
                value.speedKmh
            )
            .putInt("hardBrakes", brakes)
            .putLong(
                "lastSampleElapsedMs",
                now
            )
            .putString(
                "status",
                description
            )
            .apply()

        Log.d(
            TAG,
            "Vehicle speed: $description"
        )

        showNotification(description)
    }

    private fun markUnavailable(why: String) {

        val wasConnected =
            prefs.getBoolean("connected", false)

        if (wasConnected) {
            detector.reset()
        }

        prefs.edit()
            .putBoolean("connected", false)
            .putString("status", why)
            .apply()

        if (wasConnected) {
            Log.w(TAG, why)
            showNotification(why)
        }
    }

    private fun notification(text: String): Notification {

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        val stopAction = PendingIntent.getService(
            this,
            1,
            Intent(
                this,
                PleosVehicleMonitorService::class.java
            ).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_dialog_info
            )
            .setContentTitle(
                "화내비 · 차량 신호 모니터링"
            )
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        android.R.drawable.ic_media_pause
                    ),
                    "중지",
                    stopAction
                ).build()
            )
            .build()
    }

    private fun showNotification(text: String) {
        runCatching {
            notifications.notify(
                NOTIFICATION_ID,
                notification(text)
            )
        }.onFailure {
            Log.w(
                TAG,
                "Notification update failed",
                it
            )
        }
    }

    override fun onDestroy() {

        active = false

        mainHandler.removeCallbacks(watchdog)

        runCatching {
            propertyManager?.unregisterCallback(
                speedListener
            )
        }

        subscribed = false
        propertyManager = null

        runCatching {
            car?.disconnect()
        }

        car = null

        prefs.edit()
            .putBoolean("connected", false)
            .putString(
                "status",
                "모니터 종료"
            )
            .apply()

        super.onDestroy()
    }
}
