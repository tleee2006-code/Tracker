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
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.shipper.tracker.ui.MainActivity

class GpsTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "shipper_gps_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_STOP = "ACTION_STOP"

        const val EXTRA_SHIFT_NAME = "EXTRA_SHIFT_NAME"

        const val BROADCAST_LOCATION_UPDATE = "com.shipper.tracker.LOCATION_UPDATE"
        const val EXTRA_DISTANCE_KM = "EXTRA_DISTANCE_KM"
        const val EXTRA_SPEED_KMH = "EXTRA_SPEED_KMH"
        const val EXTRA_DURATION_SEC = "EXTRA_DURATION_SEC"

        var isRunning = false
        var isPaused = false
        var currentShiftKm = 0.0
        var currentShiftSeconds = 0L
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastLocation: Location? = null
    private var shiftName: String = "Ca Chạy"
    private var timerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        // Khởi tạo callback định vị với bộ lọc chống nhiễu
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (isPaused) return

                for (location in result.locations) {
                    processNewLocation(location)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                shiftName = intent.getStringExtra(EXTRA_SHIFT_NAME) ?: "Ca Chạy"
                startTracking()
            }
            ACTION_PAUSE -> {
                isPaused = true
                updateNotification("Đang tạm dừng GPS...")
            }
            ACTION_RESUME -> {
                isPaused = false
                lastLocation = null // Tránh bị nhảy bước dài khi vừa bật lại
                updateNotification("Đang theo dõi vị trí...")
            }
            ACTION_STOP -> {
                stopTracking()
            }
        }

        return START_STICKY
    }

    private fun startTracking() {
        if (isRunning) return

        isRunning = true
        isPaused = false
        currentShiftKm = 0.0
        currentShiftSeconds = 0L
        lastLocation = null

        // 1. Giữ CPU không ngủ sâu (WakeLock)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShipperTracker:GpsWakeLock").apply {
            acquire(12 * 60 * 60 * 1000L) // Tối đa 12 tiếng ca chạy
        }

        // 2. Kích hoạt Foreground Service chuẩn Android 14/15
        val notification = buildNotification("Bắt đầu ca: 0.00 km")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 3. Yêu cầu GPS từ FusedLocationProvider
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(2000L)
            .setMinUpdateDistanceMeters(10f) // Chỉ gọi khi di chuyển tối thiểu 10m
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            stopSelf()
            return
        }

        // 4. Bật bộ đếm thời gian ca
        timerThread = Thread {
            while (isRunning) {
                try {
                    Thread.sleep(1000)
                    if (!isPaused) {
                        currentShiftSeconds++
                        broadcastUpdate(0f)
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        timerThread?.start()
    }

    // Thuật toán lọc GPS chống Drift / Nhảy vọt
    private fun processNewLocation(newLoc: Location) {
        // Lọc 1: Bỏ qua điểm nếu sai số vệ tinh quá lớn (> 15 mét)
        if (!newLoc.hasAccuracy() || newLoc.accuracy > 15f) {
            return
        }

        val prev = lastLocation
        if (prev == null) {
            lastLocation = newLoc
            return
        }

        val distanceMeters = prev.distanceTo(newLoc)
        val timeDeltaSec = (newLoc.time - prev.time) / 1000.0

        if (timeDeltaSec <= 0) return

        val speedMs = distanceMeters / timeDeltaSec
        val speedKmh = (speedMs * 3.6).toFloat()

        // Lọc 2: Bỏ qua bước nhảy vọt phi thực tế của xe máy trong phố (> 90 km/h)
        if (speedKmh > 90f) {
            return
        }

        // Lọc 3: Chống trôi GPS khi đứng yên (Vận tốc < 1.2 m/s hoặc khoảng cách < 10m)
        if (speedMs < 1.2 && distanceMeters < 10f) {
            return
        }

        // Tọa độ hợp lệ -> Cộng dồn quãng đường
        val deltaKm = distanceMeters / 1000.0
        currentShiftKm += deltaKm
        lastLocation = newLoc

        // Cập nhật thông báo thanh trạng thái & Broadcast cho giao diện
        updateNotification(String.format("Đã chạy: %.2f km (%.0f km/h)", currentShiftKm, speedKmh))
        broadcastUpdate(speedKmh)
    }

    private fun broadcastUpdate(speedKmh: Float) {
        val intent = Intent(BROADCAST_LOCATION_UPDATE).apply {
            putExtra(EXTRA_DISTANCE_KM, currentShiftKm)
            putExtra(EXTRA_SPEED_KMH, speedKmh)
            putExtra(EXTRA_DURATION_SEC, currentShiftSeconds)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun stopTracking() {
        isRunning = false
        isPaused = false

        // DỪNG CAN THIỆP GPS HOÀN TOÀN: Hủy lắng nghe vị trí ngay lập tức
        fusedLocationClient.removeLocationUpdates(locationCallback)

        // Giải phóng WakeLock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null

        timerThread?.interrupt()
        timerThread = null

        // Tắt dịch vụ nền và hủy thông báo
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
    }
}
