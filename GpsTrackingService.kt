package com.shipper.tracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.shipper.tracker.ui.MainActivity

class GpsTrackingService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shiftName: String = "Ca Chạy"
    private var timerThread: Thread? = null
    private var isServiceStarted = false

    companion object {
        const val PREFS_NAME = "active_shift_prefs"
        const val KEY_IS_RUNNING = "key_is_running"
        const val KEY_IS_PAUSED = "key_is_paused"
        const val KEY_IS_STANDBY = "key_is_standby"
        const val KEY_SHIFT_NAME = "key_shift_name"
        const val KEY_START_TIME = "key_start_time"
        const val KEY_SHIFT_KM = "key_shift_km"
        const val KEY_SHIFT_SEC = "key_shift_sec"

        const val CHANNEL_ID = "channel_shipper_gps_v2"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.shipper.tracker.ACTION_START"
        const val ACTION_PAUSE = "com.shipper.tracker.ACTION_PAUSE"
        const val ACTION_RESUME = "com.shipper.tracker.ACTION_RESUME"
        const val ACTION_STANDBY = "com.shipper.tracker.ACTION_STANDBY"
        const val ACTION_RESUME_STANDBY = "com.shipper.tracker.ACTION_RESUME_STANDBY"
        const val ACTION_STOP = "com.shipper.tracker.ACTION_STOP"

        const val EXTRA_SHIFT_NAME = "extra_shift_name"
        const val EXTRA_START_TIME = "extra_start_time"
        const val BROADCAST_LOCATION_UPDATE = "com.shipper.tracker.LOCATION_UPDATE"
        const val EXTRA_DISTANCE_KM = "extra_distance_km"
        const val EXTRA_SPEED_KMH = "extra_speed_kmh"
        const val EXTRA_DURATION_SEC = "extra_duration_sec"
        const val EXTRA_IS_PAUSED = "extra_is_paused"
        const val EXTRA_IS_STANDBY = "extra_is_standby"
        const val EXTRA_GPS_STATUS = "extra_gps_status"

        var isRunning: Boolean = false
        var isPaused: Boolean = false
        var isStandby: Boolean = false
        var currentShiftKm: Double = 0.0
        var currentShiftSeconds: Long = 0L
        var currentGpsStatus: String = "Đang kết nối GPS..."
        var activeStartTime: String = ""
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()

        // Khôi phục trạng thái ca nếu service được hệ thống tự động tái sinh (START_STICKY)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IS_RUNNING, false)) {
            shiftName = prefs.getString(KEY_SHIFT_NAME, "Ca Chạy") ?: "Ca Chạy"
            activeStartTime = prefs.getString(KEY_START_TIME, "") ?: ""
            currentShiftKm = prefs.getFloat(KEY_SHIFT_KM, 0f).toDouble()
            currentShiftSeconds = prefs.getLong(KEY_SHIFT_SEC, 0L)
            isPaused = prefs.getBoolean(KEY_IS_PAUSED, false)
            isStandby = prefs.getBoolean(KEY_IS_STANDBY, false)
            isRunning = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY

        when (action) {
            ACTION_START -> {
                val newName = intent.getStringExtra(EXTRA_SHIFT_NAME) ?: "Ca Chạy"
                val newStart = intent.getStringExtra(EXTRA_START_TIME) ?: ""
                
                // Nếu là ca mới tinh thì nhận tên và giờ mới
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val isAlreadyRunning = prefs.getBoolean(KEY_IS_RUNNING, false)
                if (!isAlreadyRunning) {
                    shiftName = newName
                    activeStartTime = newStart
                    currentShiftKm = 0.0
                    currentShiftSeconds = 0L
                } else {
                    // Nếu đang phục hồi ca cũ bị vuốt app thì giữ nguyên số km và số giây!
                    shiftName = prefs.getString(KEY_SHIFT_NAME, newName) ?: newName
                    activeStartTime = prefs.getString(KEY_START_TIME, newStart) ?: newStart
                    currentShiftKm = prefs.getFloat(KEY_SHIFT_KM, currentShiftKm.toFloat()).toDouble()
                    currentShiftSeconds = prefs.getLong(KEY_SHIFT_SEC, currentShiftSeconds)
                }
                
                startTracking()
            }
            ACTION_PAUSE -> {
                isPaused = true
                lastLocation = null
                currentGpsStatus = "⏸ Đã tạm dừng (Đã ngắt GPS)"
                persistState()
                removeGpsUpdates()
                updateNotification("⏸ Đang tạm dừng ca...")
                broadcastUpdate(0f)
            }
            ACTION_RESUME -> {
                isPaused = false
                lastLocation = null
                currentGpsStatus = "🟢 Đang kết nối GPS 1s/lần..."
                persistState()
                requestGpsUpdates()
                updateNotification("● Đang tiếp tục theo dõi 1s/lần...")
                broadcastUpdate(0f)
            }
            ACTION_STANDBY -> {
                isStandby = true
                lastLocation = null
                currentGpsStatus = "☕ Chế độ chờ (Đã ngắt GPS, đồng hồ chạy)"
                persistState()
                removeGpsUpdates()
                updateNotification("☕ Đang chế độ chờ (Đã tắt GPS, đồng hồ vẫn chạy)")
                broadcastUpdate(0f)
            }
            ACTION_RESUME_STANDBY -> {
                isStandby = false
                lastLocation = null
                currentGpsStatus = "🟢 Đang kết nối GPS 1s/lần..."
                persistState()
                requestGpsUpdates()
                updateNotification("● Đang tiếp tục theo dõi 1s/lần...")
                broadcastUpdate(0f)
            }
            ACTION_STOP -> {
                // CHỈ KHI NGƯỜI DÙNG BẤM KẾT THÚC CA MỚI XÓA DỮ LIỆU ĐANG CHẠY
                explicitUserStop()
            }
        }

        return START_STICKY
    }

    private fun startTracking() {
        if (isServiceStarted) return
        isServiceStarted = true

        isRunning = true
        persistState()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShipperTracker:GpsWakeLock").apply {
            acquire(24 * 60 * 60 * 1000L)
        }

        val notification = buildNotification(String.format("Đang chạy: %.2f km", currentShiftKm))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!isStandby && !isPaused) {
            requestGpsUpdates()
        }

        timerThread = Thread {
            while (isRunning) {
                try {
                    Thread.sleep(1000)
                    if (!isPaused) {
                        currentShiftSeconds++
                        if (currentShiftSeconds % 3 == 0L) {
                            persistState()
                        }
                        broadcastUpdate(0f)
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        timerThread?.start()
    }

    private fun persistState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_IS_RUNNING, isRunning)
            .putBoolean(KEY_IS_PAUSED, isPaused)
            .putBoolean(KEY_IS_STANDBY, isStandby)
            .putString(KEY_SHIFT_NAME, shiftName)
            .putString(KEY_START_TIME, activeStartTime)
            .putFloat(KEY_SHIFT_KM, currentShiftKm.toFloat())
            .putLong(KEY_SHIFT_SEC, currentShiftSeconds)
            .apply()
    }

    private fun clearPersistedState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private fun requestGpsUpdates() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            }
        } catch (e: SecurityException) {
            currentGpsStatus = "⚠️ Chưa cấp quyền vị trí chính xác"
            stopSelf()
        } catch (e: Exception) {
            currentGpsStatus = "⚠️ Lỗi kết nối GPS: ${e.message}"
        }
    }

    private fun removeGpsUpdates() {
        try {
            locationManager.removeUpdates(this)
        } catch (e: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        if (isPaused || isStandby) return
        processNewLocation(location)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun processNewLocation(newLoc: Location) {
        if (newLoc.hasAccuracy() && newLoc.accuracy > 40f) {
            currentGpsStatus = String.format("📡 Sóng yếu (Độ lệch ±%.0fm)", newLoc.accuracy)
            broadcastUpdate(0f)
            return
        }

        currentGpsStatus = String.format("🟢 GPS Tốt (Độ lệch ±%.0fm)", if (newLoc.hasAccuracy()) newLoc.accuracy else 5f)

        val prev = lastLocation
        if (prev == null) {
            lastLocation = newLoc
            broadcastUpdate(0f)
            return
        }

        val distanceMeters = prev.distanceTo(newLoc)
        val timeDeltaSec = (newLoc.time - prev.time) / 1000.0

        if (timeDeltaSec <= 0) return

        val speedMs = distanceMeters / timeDeltaSec
        val speedKmh = (speedMs * 3.6).toFloat()

        if (speedKmh > 110f) {
            lastLocation = newLoc
            return
        }

        val instantSpeed = if (newLoc.hasSpeed()) (newLoc.speed * 3.6f) else speedKmh
        if (instantSpeed < 2.5f && distanceMeters < 2.5f) {
            broadcastUpdate(0f)
            return
        }

        val deltaKm = distanceMeters / 1000.0
        currentShiftKm += deltaKm
        lastLocation = newLoc

        val displaySpeed = if (newLoc.hasSpeed()) (newLoc.speed * 3.6f) else speedKmh
        updateNotification(String.format("Đã chạy: %.2f km (%.0f km/h)", currentShiftKm, displaySpeed))
        broadcastUpdate(displaySpeed)
    }

    private fun broadcastUpdate(speedKmh: Float) {
        val intent = Intent(BROADCAST_LOCATION_UPDATE).apply {
            putExtra(EXTRA_DISTANCE_KM, currentShiftKm)
            putExtra(EXTRA_SPEED_KMH, speedKmh)
            putExtra(EXTRA_DURATION_SEC, currentShiftSeconds)
            putExtra(EXTRA_IS_PAUSED, isPaused)
            putExtra(EXTRA_IS_STANDBY, isStandby)
            putExtra(EXTRA_GPS_STATUS, currentGpsStatus)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // NGƯỜI DÙNG CHỦ ĐỘNG BẤM KẾT THÚC CA
    private fun explicitUserStop() {
        isRunning = false
        isPaused = false
        isStandby = false
        isServiceStarted = false

        clearPersistedState()
        removeGpsUpdates()

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null

        timerThread?.interrupt()
        timerThread = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // KHI NGƯỜI DÙNG QUÊN BẤM KẾT THÚC, VUỐT XÓA ĐA NHIỆM APP ĐÓ LUÔN:
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 1. Tự động lưu toàn bộ dữ liệu ca chạy hiện tại vào Database để không bị mất
        if (isRunning && (currentShiftSeconds >= 10L || currentShiftKm >= 0.02)) {
            try {
                val dbHelper = com.shipper.tracker.data.DatabaseHelper(applicationContext)
                val autoShift = com.shipper.tracker.data.Shift(
                    date = com.shipper.tracker.data.TimeUtils.getTodayDate(),
                    shiftName = shiftName,
                    startTime = if (activeStartTime.isNotEmpty()) activeStartTime else com.shipper.tracker.data.TimeUtils.getTimeNow(),
                    endTime = com.shipper.tracker.data.TimeUtils.getTimeNow(),
                    durationSeconds = currentShiftSeconds,
                    distanceKm = currentShiftKm,
                    revenue = 0L,
                    fuelCost = (currentShiftKm * 350).toLong(),
                    otherCost = 0L,
                    netProfit = -((currentShiftKm * 350).toLong())
                )
                dbHelper.insertShift(autoShift)
            } catch (e: Exception) {}
        }

        // 2. Tắt hoàn toàn chạy ngầm và ngắt hẳn GPS 100% để không bị hao pin!
        explicitUserStop()
        super.onTaskRemoved(rootIntent)
    }

    // KHI HỆ THỐNG HUỶ SERVICE (DO THIẾU RAM HOẶC VUỐT APP):
    override fun onDestroy() {
        super.onDestroy()
        // TUYỆT ĐỐI KHÔNG XÓA PREFS Ở ĐÂY! PHẢI LƯU LẠI ĐỂ PHỤC HỒI!
        if (isRunning) {
            persistState()
        }
        removeGpsUpdates()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
        timerThread?.interrupt()
        timerThread = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shipper GPS Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị quãng đường đang chạy nền cho ca ship"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚴 Shipper: $shiftName")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
