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
        const val CHANNEL_ID = "channel_shipper_gps_v2"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.shipper.tracker.ACTION_START"
        const val ACTION_PAUSE = "com.shipper.tracker.ACTION_PAUSE"
        const val ACTION_RESUME = "com.shipper.tracker.ACTION_RESUME"
        const val ACTION_STANDBY = "com.shipper.tracker.ACTION_STANDBY"
        const val ACTION_RESUME_STANDBY = "com.shipper.tracker.ACTION_RESUME_STANDBY"
        const val ACTION_STOP = "com.shipper.tracker.ACTION_STOP"

        const val EXTRA_SHIFT_NAME = "extra_shift_name"
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
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                shiftName = intent.getStringExtra(EXTRA_SHIFT_NAME) ?: "Ca Chạy"
                startTracking()
            }
            ACTION_PAUSE -> {
                // TẠM DỪNG: NGẮT GPS PHẦN CỨNG ĐỂ TẮT CHẤM XANH & TIẾT KIỆM PIN
                isPaused = true
                lastLocation = null
                currentGpsStatus = "⏸ Đã tạm dừng (Đã ngắt GPS)"
                removeGpsUpdates()
                updateNotification("⏸ Đang tạm dừng ca...")
                broadcastUpdate(0f)
            }
            ACTION_RESUME -> {
                // TIẾP TỤC: KẾT NỐI LẠI GPS PHẦN CỨNG NGAY LẬP TỨC
                isPaused = false
                lastLocation = null
                currentGpsStatus = "🟢 Đang kết nối GPS 1s/lần..."
                requestGpsUpdates()
                updateNotification("● Đang tiếp tục theo dõi 1s/lần...")
                broadcastUpdate(0f)
            }
            ACTION_STANDBY -> {
                // CHẾ ĐỘ CHỜ (VÀO QUÁN / CHỜ KHÁCH): NGẮT GPS 100%, ĐỒNG HỒ VẪN CHẠY
                isStandby = true
                lastLocation = null
                currentGpsStatus = "☕ Chế độ chờ (Đã ngắt GPS, đồng hồ chạy)"
                removeGpsUpdates()
                updateNotification("☕ Đang chế độ chờ (Đã tắt GPS, đồng hồ vẫn chạy)")
                broadcastUpdate(0f)
            }
            ACTION_RESUME_STANDBY -> {
                // TIẾP TỤC LĂN BÁNH: BẬT LẠI GPS 1S/LẦN
                isStandby = false
                lastLocation = null
                currentGpsStatus = "🟢 Đang kết nối GPS 1s/lần..."
                requestGpsUpdates()
                updateNotification("● Đang tiếp tục theo dõi 1s/lần...")
                broadcastUpdate(0f)
            }
            ACTION_STOP -> {
                stopTracking()
            }
        }

        return START_STICKY
    }

    private fun startTracking() {
        if (isServiceStarted) return
        isServiceStarted = true

        isRunning = true
        isPaused = false
        isStandby = false
        currentShiftKm = 0.0
        currentShiftSeconds = 0L
        lastLocation = null
        currentGpsStatus = "🟢 Đang kết nối GPS 1s/lần..."

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShipperTracker:GpsWakeLock").apply {
            acquire(12 * 60 * 60 * 1000L)
        }

        val notification = buildNotification("Bắt đầu ca: 0.00 km")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        requestGpsUpdates()

        // Timer thời gian ca chạy ngầm (chạy liên tục kể cả khi standby)
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

    // YÊU CẦU GPS TRỰC TIẾP TỪ PHẦN CỨNG ANDROID (HIỆN TRỰC TIẾP TRONG HOẠT ĐỘNG VỊ TRÍ)
    private fun requestGpsUpdates() {
        try {
            // 1. Kích hoạt trực tiếp GPS phần cứng vệ tinh (Android sẽ ghi nhận tên app và bật chấm xanh)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            }

            // 2. Kích hoạt mạng hỗ trợ (Network Provider) để khi ngồi trong nhà vẫn có tín hiệu định vị
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

    // LocationListener callback: Nhận tọa độ trực tiếp
    override fun onLocationChanged(location: Location) {
        if (isPaused || isStandby) return
        processNewLocation(location)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // THUẬT TOÁN TÍNH QUÃNG ĐƯỜNG: TỰ ĐỘNG KHÓA ĐÈN ĐỎ & ĐẾM CHUẨN 1S/LẦN
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

        // TỰ ĐỘNG KHÓA KHI CHỜ ĐÈN ĐỎ / NGỒI TRONG NHÀ (Vận tốc < 2.5 km/h và cự ly < 2.5m)
        val instantSpeed = if (newLoc.hasSpeed()) (newLoc.speed * 3.6f) else speedKmh
        if (instantSpeed < 2.5f && distanceMeters < 2.5f) {
            // Đang đứng yên / chờ đèn đỏ -> KHÓA CỨNG, KHÔNG CỘNG NHẢY ẢO
            broadcastUpdate(0f)
            return
        }

        // Xe đang lăn bánh ngoài đường -> Cộng dồn ngay
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

    private fun stopTracking() {
        isRunning = false
        isPaused = false
        isStandby = false
        isServiceStarted = false

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
