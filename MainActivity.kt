package com.shipper.tracker.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shipper.tracker.R
import com.shipper.tracker.data.DailySummary
import com.shipper.tracker.data.DatabaseHelper
import com.shipper.tracker.data.FuelRefill
import com.shipper.tracker.data.Shift
import com.shipper.tracker.data.TimeUtils
import com.shipper.tracker.service.GpsTrackingService
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: DatabaseHelper

    // UI Elements Tab 1
    private lateinit var tvTotalOdometer: TextView
    private lateinit var tvTodayTotalKm: TextView
    private lateinit var tvTodayTotalDuration: TextView
    private lateinit var tvTodayShiftCompleted: TextView
    private lateinit var tvTodayDateBadge: TextView

    private lateinit var cardActiveShift: View
    private lateinit var tvActiveShiftTitle: TextView
    private lateinit var tvShiftKm: TextView
    private lateinit var tvShiftTime: TextView
    private lateinit var tvGpsStatus: TextView

    private lateinit var btnStartShift: Button
    private lateinit var layoutRunningControls: LinearLayout
    private lateinit var btnStandbyShift: Button
    private lateinit var btnPauseShift: Button
    private lateinit var btnStopShift: Button
    private lateinit var btnQuickRefill: Button

    // Tab Views
    private lateinit var viewTracker: View
    private lateinit var viewHistory: View
    private lateinit var viewFuel: View

    private lateinit var listDailyHistory: ListView
    private lateinit var listFuelHistory: ListView
    private lateinit var btnClearAllHistory: Button
    private lateinit var btnClearAllFuel: Button

    private lateinit var btnTabTracker: Button
    private lateinit var btnTabHistory: Button
    private lateinit var btnTabFuel: Button

    private var activeShiftStartTime: String = ""
    private var activeShiftName: String = "Ca 1"
    private var baseTodayKm: Double = 0.0
    private var baseTodaySeconds: Long = 0L

    // Bộ hẹn giờ cập nhật UI trên màn hình (CHỈ ĐỌC, KHÔNG CỘNG TRÙNG VỚI SERVICE)
    private val uiTimerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiTimerRunnable = object : Runnable {
        override fun run() {
            if (GpsTrackingService.isRunning) {
                val currentTodayKm = baseTodayKm + GpsTrackingService.currentShiftKm
                val currentTodaySec = baseTodaySeconds + GpsTrackingService.currentShiftSeconds

                tvTodayTotalKm.text = String.format(Locale.US, "%.2f", currentTodayKm)
                tvTodayTotalDuration.text = formatSeconds(currentTodaySec)
                tvShiftKm.text = String.format(Locale.US, "Ca: %.2f km", GpsTrackingService.currentShiftKm)
                tvShiftTime.text = formatSeconds(GpsTrackingService.currentShiftSeconds)
            }
            uiTimerHandler.postDelayed(this, 1000)
        }
    }

    // Broadcast nhận dữ liệu thời gian thực 1 giây/lần từ GPS Service ngầm
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GpsTrackingService.BROADCAST_LOCATION_UPDATE) {
                val km = intent.getDoubleExtra(GpsTrackingService.EXTRA_DISTANCE_KM, 0.0)
                val speed = intent.getFloatExtra(GpsTrackingService.EXTRA_SPEED_KMH, 0f)
                val seconds = intent.getLongExtra(GpsTrackingService.EXTRA_DURATION_SEC, 0L)
                val paused = intent.getBooleanExtra(GpsTrackingService.EXTRA_IS_PAUSED, false)
                val standby = intent.getBooleanExtra(GpsTrackingService.EXTRA_IS_STANDBY, false)
                val gpsStatus = intent.getStringExtra(GpsTrackingService.EXTRA_GPS_STATUS) ?: ""

                val speedStr = when {
                    paused -> "Tạm dừng"
                    standby -> "Đang chờ (Tắt GPS)"
                    else -> String.format(Locale.US, "%.0f km/h", speed)
                }
                tvShiftKm.text = String.format(Locale.US, "Ca: %.2f km (%s)", km, speedStr)
                tvShiftTime.text = formatSeconds(seconds)
                if (gpsStatus.isNotEmpty()) {
                    tvGpsStatus.text = gpsStatus
                }

                val currentTodayKm = baseTodayKm + km
                val currentTodaySec = baseTodaySeconds + seconds
                tvTodayTotalKm.text = String.format(Locale.US, "%.2f", currentTodayKm)
                tvTodayTotalDuration.text = formatSeconds(currentTodaySec)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (!fineLocationGranted) {
            Toast.makeText(this, "Cần cấp quyền 'Vị trí chính xác' để đo quãng đường!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Đã cấp quyền vị trí chính xác!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = DatabaseHelper(this)

        initViews()
        setupListeners()
        checkAndRequestPermissions()
        checkBatteryOptimization()
        restorePersistedShiftIfNeeded()
        refreshTodayCenterMetrics()
        syncServiceState()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GpsTrackingService.BROADCAST_LOCATION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(locationReceiver, filter)
        }
        restorePersistedShiftIfNeeded()
        refreshTodayCenterMetrics()
        syncServiceState()
        uiTimerHandler.post(uiTimerRunnable)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(locationReceiver)
        } catch (e: Exception) {}
        uiTimerHandler.removeCallbacks(uiTimerRunnable)
    }

    private fun initViews() {
        tvTotalOdometer = findViewById(R.id.tv_total_odometer)
        tvTodayDateBadge = findViewById(R.id.tv_today_date_badge)
        tvTodayTotalKm = findViewById(R.id.tv_today_total_km)
        tvTodayTotalDuration = findViewById(R.id.tv_today_total_duration)
        tvTodayShiftCompleted = findViewById(R.id.tv_today_shift_completed)

        cardActiveShift = findViewById(R.id.card_active_shift)
        tvActiveShiftTitle = findViewById(R.id.tv_active_shift_title)
        tvShiftKm = findViewById(R.id.tv_shift_km)
        tvShiftTime = findViewById(R.id.tv_shift_time)
        tvGpsStatus = findViewById(R.id.tv_gps_status)

        btnStartShift = findViewById(R.id.btn_start_shift)
        layoutRunningControls = findViewById(R.id.layout_running_controls)
        btnStandbyShift = findViewById(R.id.btn_standby_shift)
        btnPauseShift = findViewById(R.id.btn_pause_shift)
        btnStopShift = findViewById(R.id.btn_stop_shift)
        btnQuickRefill = findViewById(R.id.btn_quick_refill)

        viewTracker = findViewById(R.id.view_tracker)
        viewHistory = findViewById(R.id.view_history)
        viewFuel = findViewById(R.id.view_fuel)

        listDailyHistory = findViewById(R.id.list_daily_history)
        listFuelHistory = findViewById(R.id.list_fuel_history)
        btnClearAllHistory = findViewById(R.id.btn_clear_all_history)
        btnClearAllFuel = findViewById(R.id.btn_clear_all_fuel)

        btnTabTracker = findViewById(R.id.btn_tab_tracker)
        btnTabHistory = findViewById(R.id.btn_tab_history)
        btnTabFuel = findViewById(R.id.btn_tab_fuel)
    }

    private fun setupListeners() {
        btnTabTracker.setOnClickListener { switchTab(1) }
        btnTabHistory.setOnClickListener { 
            switchTab(2)
            loadDailyHistory()
        }
        btnTabFuel.setOnClickListener { 
            switchTab(3)
            loadFuelHistory()
        }

        // BẮT ĐẦU CA: BẢO VỆ CA CŨ & CHẠY NGAY
        btnStartShift.setOnClickListener {
            startShiftInstantly()
        }

        // NÚT CHẾ ĐỘ CHỜ (VÀO QUÁN / CHỜ KHÁCH) - TẮT GPS 100%, ĐỒNG HỒ VẪN CHẠY
        btnStandbyShift.setOnClickListener {
            toggleStandbyInstantly()
        }

        // TẠM DỪNG / TIẾP TỤC: 1 CHẠM ĐỔI TRẠNG THÁI NGAY
        btnPauseShift.setOnClickListener {
            togglePauseInstantly()
        }

        // KẾT THÚC CA: MỞ FORM DARK THEME ĐẸP MẮT
        btnStopShift.setOnClickListener {
            showEndShiftDarkDialog()
        }

        // ĐỔ XĂNG
        btnQuickRefill.setOnClickListener {
            showRefillDialog()
        }

        // XÓA TẤT CẢ CA (HOÀN TOÀN ĐỘC LẬP, KHÔNG CHẠM VÀO XĂNG)
        btnClearAllHistory.setOnClickListener {
            showClearAllShiftsConfirmDialog()
        }

        // XÓA TẤT CẢ LỊCH SỬ XĂNG (ĐỘC LẬP)
        btnClearAllFuel.setOnClickListener {
            showClearAllFuelConfirmDialog()
        }
    }

    private fun switchTab(tab: Int) {
        viewTracker.visibility = if (tab == 1) View.VISIBLE else View.GONE
        viewHistory.visibility = if (tab == 2) View.VISIBLE else View.GONE
        viewFuel.visibility = if (tab == 3) View.VISIBLE else View.GONE

        btnTabTracker.setTextColor(ContextCompat.getColor(this, if (tab == 1) R.color.accent_blue else R.color.text_muted))
        btnTabHistory.setTextColor(ContextCompat.getColor(this, if (tab == 2) R.color.accent_blue else R.color.text_muted))
        btnTabFuel.setTextColor(ContextCompat.getColor(this, if (tab == 3) R.color.accent_blue else R.color.text_muted))
    }

    private fun restorePersistedShiftIfNeeded() {
        val prefs = getSharedPreferences(GpsTrackingService.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(GpsTrackingService.KEY_IS_RUNNING, false)) {
            activeShiftName = prefs.getString(GpsTrackingService.KEY_SHIFT_NAME, "Ca 1") ?: "Ca 1"
            activeShiftStartTime = prefs.getString(GpsTrackingService.KEY_START_TIME, "") ?: ""
            GpsTrackingService.currentShiftKm = prefs.getFloat(GpsTrackingService.KEY_SHIFT_KM, 0f).toDouble()
            GpsTrackingService.currentShiftSeconds = prefs.getLong(GpsTrackingService.KEY_SHIFT_SEC, 0L)
            GpsTrackingService.isPaused = prefs.getBoolean(GpsTrackingService.KEY_IS_PAUSED, false)
            GpsTrackingService.isStandby = prefs.getBoolean(GpsTrackingService.KEY_IS_STANDBY, false)
            GpsTrackingService.isRunning = true

            // Khởi động lại service nền ngay nếu bị vuốt tắt app
            val serviceIntent = Intent(this, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_START
                putExtra(GpsTrackingService.EXTRA_SHIFT_NAME, activeShiftName)
                putExtra(GpsTrackingService.EXTRA_START_TIME, activeShiftStartTime)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    // Luôn tính toán theo Giờ Việt Nam chuẩn xác
    private fun refreshTodayCenterMetrics() {
        val todayStr = TimeUtils.getTodayDate()
        val dateDisplay = TimeUtils.getDisplayDate()
        tvTodayDateBadge.text = "HÔM NAY: $dateDisplay"

        val stats = dbHelper.getTodayStats(todayStr)
        baseTodayKm = stats.first
        baseTodaySeconds = stats.second
        val completedCount = stats.third

        val displayKm = if (GpsTrackingService.isRunning) baseTodayKm + GpsTrackingService.currentShiftKm else baseTodayKm
        val displaySec = if (GpsTrackingService.isRunning) baseTodaySeconds + GpsTrackingService.currentShiftSeconds else baseTodaySeconds

        tvTodayTotalKm.text = String.format(Locale.US, "%.2f", displayKm)
        tvTodayTotalDuration.text = formatSeconds(displaySec)
        tvTodayShiftCompleted.text = "$completedCount ca xong"

        val totalOdo = dbHelper.getTotalOdometer()
        tvTotalOdometer.text = String.format(Locale.US, "Odo tổng: %.1f km", totalOdo)

        if (!GpsTrackingService.isRunning) {
            btnStartShift.text = "▶ BẮT ĐẦU CA MỚI (Ca ${completedCount + 1})"
        }
    }

    private fun syncServiceState() {
        if (GpsTrackingService.isRunning) {
            btnStartShift.visibility = View.GONE
            layoutRunningControls.visibility = View.VISIBLE
            cardActiveShift.visibility = View.VISIBLE
            tvGpsStatus.text = GpsTrackingService.currentGpsStatus

            if (GpsTrackingService.isStandby) {
                btnStandbyShift.text = "🛵 TIẾP TỤC LĂN BÁNH (BẬT GPS)"
                btnStandbyShift.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
                tvActiveShiftTitle.text = "☕ CHẾ ĐỘ CHỜ: $activeShiftName (ĐÃ TẮT GPS)"
            } else {
                btnStandbyShift.text = "☕ CHẾ ĐỘ CHỜ (VÀO QUÁN / TẮT GPS)"
                btnStandbyShift.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_orange)
                tvActiveShiftTitle.text = if (GpsTrackingService.isPaused) "⏸ ĐÃ TẠM DỪNG: $activeShiftName" else "● ĐANG BẬT GPS: $activeShiftName"
            }

            btnPauseShift.text = if (GpsTrackingService.isPaused) "▶ TIẾP TỤC" else "⏸ TẠM DỪNG"
            tvShiftKm.text = String.format(Locale.US, "Ca: %.2f km", GpsTrackingService.currentShiftKm)
            tvShiftTime.text = formatSeconds(GpsTrackingService.currentShiftSeconds)
        } else {
            btnStartShift.visibility = View.VISIBLE
            layoutRunningControls.visibility = View.GONE
            cardActiveShift.visibility = View.GONE
        }
    }

    // 1 CHẠM BẮT ĐẦU CA: TỰ ĐỘNG BẢO TOÀN CA CŨ NẾU QUÊN BẤM LƯU
    private fun startShiftInstantly() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Vui lòng cấp quyền 'Vị trí chính xác' để bắt đầu ca!", Toast.LENGTH_LONG).show()
            checkAndRequestPermissions()
            return
        }

        // TỰ ĐỘNG LƯU PHỤC HỒI NẾU CÓ CA CŨ CHƯA KẾT THÚC (TRÁNH MẤT 24 PHÚT KHI QUÊN LƯU)
        val oldKm = GpsTrackingService.currentShiftKm
        val oldSec = GpsTrackingService.currentShiftSeconds
        if (oldSec >= 60L || oldKm >= 0.05) {
            val autoShift = Shift(
                date = TimeUtils.getTodayDate(),
                shiftName = activeShiftName,
                startTime = if (activeShiftStartTime.isNotEmpty()) activeShiftStartTime else TimeUtils.getTimeNow(),
                endTime = TimeUtils.getTimeNow(),
                durationSeconds = oldSec,
                distanceKm = oldKm,
                revenue = 0L,
                fuelCost = (oldKm * 350).toLong(),
                otherCost = 0L,
                netProfit = -((oldKm * 350).toLong())
            )
            dbHelper.insertShift(autoShift)
            Toast.makeText(this, "Đã tự động bảo toàn thời gian của $activeShiftName!", Toast.LENGTH_SHORT).show()
        }

        refreshTodayCenterMetrics()

        val todayStr = TimeUtils.getTodayDate()
        val count = dbHelper.getTodayStats(todayStr).third
        activeShiftName = "Ca ${count + 1}"
        activeShiftStartTime = TimeUtils.getTimeNow()

        GpsTrackingService.isRunning = true
        GpsTrackingService.isPaused = false
        GpsTrackingService.isStandby = false
        GpsTrackingService.currentShiftKm = 0.0
        GpsTrackingService.currentShiftSeconds = 0L
        GpsTrackingService.currentGpsStatus = "🟢 Đang kết nối GPS phần cứng..."

        btnStartShift.visibility = View.GONE
        layoutRunningControls.visibility = View.VISIBLE
        cardActiveShift.visibility = View.VISIBLE

        btnStandbyShift.text = "☕ CHẾ ĐỘ CHỜ (VÀO QUÁN / TẮT GPS)"
        btnStandbyShift.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_orange)
        btnPauseShift.text = "⏸ TẠM DỪNG"
        tvActiveShiftTitle.text = "● ĐANG BẬT GPS: $activeShiftName"
        tvShiftKm.text = "Ca: 0.00 km"
        tvShiftTime.text = "00:00:00"
        tvGpsStatus.text = "🟢 Đang kết nối GPS phần cứng..."

        val serviceIntent = Intent(this, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_START
            putExtra(GpsTrackingService.EXTRA_SHIFT_NAME, activeShiftName)
            putExtra(GpsTrackingService.EXTRA_START_TIME, activeShiftStartTime)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "Đã kích hoạt GPS cho $activeShiftName!", Toast.LENGTH_SHORT).show()
    }

    // 1 CHẠM BẬT / TẮT CHẾ ĐỘ CHỜ (NGẮT GPS PHẦN CỨNG 100%, ĐỒNG HỒ VẪN CHẠY)
    private fun toggleStandbyInstantly() {
        if (!GpsTrackingService.isRunning) return

        if (!GpsTrackingService.isStandby) {
            // VÀO CHẾ ĐỘ CHỜ: TẮT GPS NGAY
            GpsTrackingService.isStandby = true
            GpsTrackingService.currentGpsStatus = "☕ Chế độ chờ (Đã ngắt GPS, đồng hồ chạy)"
            btnStandbyShift.text = "🛵 TIẾP TỤC LĂN BÁNH (BẬT GPS)"
            btnStandbyShift.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
            tvActiveShiftTitle.text = "☕ CHẾ ĐỘ CHỜ: $activeShiftName (ĐÃ TẮT GPS)"
            tvGpsStatus.text = "☕ Chế độ chờ (Đã ngắt GPS, đồng hồ chạy)"

            val intent = Intent(this, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_STANDBY
            }
            startService(intent)
            Toast.makeText(this, "Đã ngắt GPS phần cứng! Đang tính thời gian ca.", Toast.LENGTH_SHORT).show()
        } else {
            // LĂN BÁNH LẠI: BẬT LẠI GPS 1S/LẦN
            GpsTrackingService.isStandby = false
            GpsTrackingService.currentGpsStatus = "🟢 Đang kết nối GPS 1s/lần..."
            btnStandbyShift.text = "☕ CHẾ ĐỘ CHỜ (VÀO QUÁN / TẮT GPS)"
            btnStandbyShift.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_orange)
            tvActiveShiftTitle.text = "● ĐANG BẬT GPS: $activeShiftName"
            tvGpsStatus.text = "🟢 Đang kết nối GPS phần cứng..."

            val intent = Intent(this, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_RESUME_STANDBY
            }
            startService(intent)
            Toast.makeText(this, "Đã bật lại định vị GPS phần cứng!", Toast.LENGTH_SHORT).show()
        }
    }

    // 1 CHẠM TẠM DỪNG / TIẾP TỤC
    private fun togglePauseInstantly() {
        if (GpsTrackingService.isPaused) {
            GpsTrackingService.isPaused = false
            GpsTrackingService.currentGpsStatus = "🟢 Đang kết nối GPS 1s/lần..."
            val intent = Intent(this, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_RESUME
            }
            startService(intent)
            btnPauseShift.text = "⏸ TẠM DỪNG"
            tvActiveShiftTitle.text = if (GpsTrackingService.isStandby) "☕ CHẾ ĐỘ CHỜ: $activeShiftName (ĐÃ TẮT GPS)" else "● ĐANG BẬT GPS: $activeShiftName"
            tvGpsStatus.text = "🟢 Đang kết nối GPS phần cứng..."
        } else {
            GpsTrackingService.isPaused = true
            GpsTrackingService.currentGpsStatus = "⏸ Đã tạm dừng (Đã ngắt GPS)"
            val intent = Intent(this, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_PAUSE
            }
            startService(intent)
            btnPauseShift.text = "▶ TIẾP TỤC"
            tvActiveShiftTitle.text = "⏸ ĐÃ TẠM DỪNG: $activeShiftName"
            tvGpsStatus.text = "⏸ Đã tạm dừng (Đã ngắt GPS)"
        }
    }

    // FORM KẾT THÚC CA DARK THEME
    private fun showEndShiftDarkDialog() {
        val distance = GpsTrackingService.currentShiftKm
        val durationSec = GpsTrackingService.currentShiftSeconds

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_end_shift, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvTitle = dialogView.findViewById<TextView>(R.id.dialog_end_title)
        val tvKm = dialogView.findViewById<TextView>(R.id.dialog_shift_km)
        val tvTime = dialogView.findViewById<TextView>(R.id.dialog_shift_time)
        val etRevenue = dialogView.findViewById<EditText>(R.id.et_revenue)
        val etFuel = dialogView.findViewById<EditText>(R.id.et_fuel)
        val etOther = dialogView.findViewById<EditText>(R.id.et_other)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_dialog_cancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_dialog_save)

        tvTitle.text = "⏹ KẾT THÚC $activeShiftName"
        tvKm.text = String.format(Locale.US, "%.2f km", distance)
        tvTime.text = formatSeconds(durationSec)

        val estFuel = (distance * 350).toLong()
        etFuel.setText(estFuel.toString())

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val rev = etRevenue.text.toString().toLongOrNull() ?: 0L
            val fuel = etFuel.text.toString().toLongOrNull() ?: 0L
            val other = etOther.text.toString().toLongOrNull() ?: 0L
            val net = rev - fuel - other

            val todayStr = TimeUtils.getTodayDate()
            val endTimeStr = TimeUtils.getTimeNow()

            val stopIntent = Intent(this, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_STOP
            }
            startService(stopIntent)

            GpsTrackingService.isRunning = false
            GpsTrackingService.isPaused = false
            GpsTrackingService.isStandby = false
            btnStartShift.visibility = View.VISIBLE
            layoutRunningControls.visibility = View.GONE
            cardActiveShift.visibility = View.GONE

            val shift = Shift(
                date = todayStr,
                shiftName = activeShiftName,
                startTime = activeShiftStartTime,
                endTime = endTimeStr,
                durationSeconds = durationSec,
                distanceKm = distance,
                revenue = rev,
                fuelCost = fuel,
                otherCost = other,
                netProfit = net
            )
            dbHelper.insertShift(shift)

            dialog.dismiss()
            refreshTodayCenterMetrics()

            val profitText = if (net >= 0) String.format(Locale.US, "+%,d đ", net) else String.format(Locale.US, "-%,d đ", abs(net))
            Toast.makeText(this, "Đã lưu $activeShiftName! Lời: $profitText", Toast.LENGTH_LONG).show()
        }

        dialog.show()
    }

    // Dialog Đổ Xăng
    private fun showRefillDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val etAmount = EditText(this).apply { 
            hint = "Số tiền đổ xăng (VNĐ)" 
            setText("50000")
        }
        val etPrice = EditText(this).apply { 
            hint = "Giá xăng (VNĐ/lít)" 
            setText("21500")
        }
        layout.addView(etAmount)
        layout.addView(etPrice)

        AlertDialog.Builder(this)
            .setTitle("⛽ Ghi nhận Đổ Xăng")
            .setView(layout)
            .setPositiveButton("Lưu") { _, _ ->
                val amount = etAmount.text.toString().toLongOrNull() ?: 50000L
                val price = etPrice.text.toString().toLongOrNull() ?: 21500L
                val refill = dbHelper.recordFuelRefill(amount, price)

                val msg = String.format(
                    Locale.US,
                    "Đã lưu đổ xăng!\nĐoạn đường từ lần trước: %.1f km\nChi phí: %,.0f đ/km",
                    refill.kmSinceLast, refill.costPerKm
                )
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // XÓA TẤT CẢ CA CHẠY (KHÔNG ẢNH HƯỞNG ĐẾN XĂNG)
    private fun showClearAllShiftsConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Xóa toàn bộ ca chạy?")
            .setMessage("Chỉ xóa lịch sử các ca chạy. Lịch sử đổ xăng vẫn được giữ nguyên độc lập.")
            .setPositiveButton("XÓA TẤT CẢ CA") { _, _ ->
                dbHelper.clearAllShifts()
                refreshTodayCenterMetrics()
                loadDailyHistory()
                Toast.makeText(this, "Đã xóa toàn bộ ca chạy!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // XÓA TẤT CẢ LỊCH SỬ XĂNG (KHÔNG ẢNH HƯỞNG ĐẾN CA CHẠY)
    private fun showClearAllFuelConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Xóa toàn bộ lịch sử xăng?")
            .setMessage("Chỉ xóa số liệu các lần đổ xăng. Lịch sử các ca chạy và số km vẫn giữ nguyên.")
            .setPositiveButton("XÓA HẾT XĂNG") { _, _ ->
                dbHelper.clearAllFuel()
                loadFuelHistory()
                Toast.makeText(this, "Đã xóa sạch lịch sử xăng!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // Tải danh sách theo Ngày dạng DÒNG CHẢY TRỰC TIẾP TRONG APP (CÓ THU NHẬP / GIỜ CẢ NGÀY)
    private fun loadDailyHistory() {
        val list = dbHelper.getDailySummaries()
        val adapter = object : ArrayAdapter<DailySummary>(this, 0, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_daily_summary, parent, false)
                val item = getItem(position)!!

                val tvDate = view.findViewById<TextView>(R.id.item_date)
                val tvShiftCount = view.findViewById<TextView>(R.id.item_shift_count)
                val tvTotalKm = view.findViewById<TextView>(R.id.item_total_km)
                val tvTotalDuration = view.findViewById<TextView>(R.id.item_total_duration)
                val tvTotalRev = view.findViewById<TextView>(R.id.item_total_rev)
                val tvTotalProfit = view.findViewById<TextView>(R.id.item_total_profit)
                val tvEarningsPerHour = view.findViewById<TextView>(R.id.item_earnings_per_hour)
                val btnDeleteDay = view.findViewById<Button>(R.id.btn_delete_day)
                val tvExpandHint = view.findViewById<TextView>(R.id.tv_expand_hint)
                val shiftsContainer = view.findViewById<LinearLayout>(R.id.shifts_container)

                tvDate.text = item.date
                tvShiftCount.text = "${item.shiftCount} ca chạy"
                tvTotalKm.text = String.format(Locale.US, "%.2f km", item.totalDistanceKm)
                tvTotalDuration.text = formatSeconds(item.totalDurationSeconds)
                tvTotalRev.text = String.format(Locale.US, "%,d đ", item.totalRevenue)

                // Lợi nhuận: Dương xanh dương (+), Âm đỏ (-)
                val profit = item.totalProfit
                if (profit >= 0) {
                    tvTotalProfit.text = String.format(Locale.US, "+%,d đ", profit)
                    tvTotalProfit.setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                } else {
                    tvTotalProfit.text = String.format(Locale.US, "-%,d đ", abs(profit))
                    tvTotalProfit.setTextColor(ContextCompat.getColor(context, R.color.accent_red))
                }

                // THU NHẬP / GIỜ TÍNH THEO CẢ NGÀY
                tvEarningsPerHour.text = String.format(Locale.US, "%,d đ/h", item.earningsPerHour)

                // XÓA RIÊNG CÁC CA CỦA NGÀY NÀY (KHÔNG ẢNH HƯỞNG ĐẾN XĂNG)
                btnDeleteDay.setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle("Xóa ngày ${item.date}?")
                        .setMessage("Bạn có chắc muốn xóa tất cả ${item.shiftCount} ca của ngày này? (Số liệu xăng vẫn an toàn).")
                        .setPositiveButton("Xóa") { _, _ ->
                            dbHelper.deleteShiftsByDate(item.date)
                            refreshTodayCenterMetrics()
                            loadDailyHistory()
                            Toast.makeText(context, "Đã xóa ngày ${item.date}", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Hủy", null)
                        .show()
                }

                // CHẠM ĐỂ MỞ RỘNG DÒNG CHẢY TỪNG CA
                view.setOnClickListener {
                    if (shiftsContainer.visibility == View.VISIBLE) {
                        shiftsContainer.visibility = View.GONE
                        tvExpandHint.text = "▼ Chạm để mở rộng dòng chảy từng ca trong ngày"
                    } else {
                        shiftsContainer.removeAllViews()
                        val shifts = dbHelper.getShiftsByDate(item.date)
                        shifts.forEach { shift ->
                            val shiftView = LayoutInflater.from(context).inflate(R.layout.item_shift_detail, shiftsContainer, false)
                            shiftView.findViewById<TextView>(R.id.shift_item_name).text = "🛵 ${shift.shiftName}"
                            shiftView.findViewById<TextView>(R.id.shift_item_time_range).text = "${shift.startTime} - ${shift.endTime}"
                            shiftView.findViewById<TextView>(R.id.shift_item_km).text = String.format(Locale.US, "Quãng đường: %.2f km", shift.distanceKm)
                            shiftView.findViewById<TextView>(R.id.shift_item_duration).text = "Thời lượng: ${formatSeconds(shift.durationSeconds)}"
                            shiftView.findViewById<TextView>(R.id.shift_item_rev_fuel).text = String.format(Locale.US, "Thu: %,d đ | Xăng: %,d đ", shift.revenue, shift.fuelCost)

                            val tvShiftProfit = shiftView.findViewById<TextView>(R.id.shift_item_profit)
                            if (shift.netProfit >= 0) {
                                tvShiftProfit.text = String.format(Locale.US, "+%,d đ", shift.netProfit)
                                tvShiftProfit.setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
                            } else {
                                tvShiftProfit.text = String.format(Locale.US, "-%,d đ", abs(shift.netProfit))
                                tvShiftProfit.setTextColor(ContextCompat.getColor(context, R.color.accent_red))
                            }

                            // SỬA CA CHẠY
                            shiftView.findViewById<ImageButton>(R.id.btn_edit_shift).setOnClickListener {
                                showEditShiftDialog(shift)
                            }

                            // XÓA RIÊNG TỪNG CA LẺ (KHÔNG ẢNH HƯỞNG ĐẾN XĂNG)
                            shiftView.findViewById<ImageButton>(R.id.btn_delete_shift).setOnClickListener {
                                dbHelper.deleteShiftById(shift.id)
                                refreshTodayCenterMetrics()
                                loadDailyHistory()
                                Toast.makeText(context, "Đã xóa ${shift.shiftName}", Toast.LENGTH_SHORT).show()
                            }

                            shiftsContainer.addView(shiftView)
                        }
                        shiftsContainer.visibility = View.VISIBLE
                        tvExpandHint.text = "▲ Chạm để thu gọn danh sách ca"
                    }
                }

                return view
            }
        }
        listDailyHistory.adapter = adapter
    }

    // Tải lịch sử đổ xăng (ĐỘC LẬP HOÀN TOÀN, CÓ NÚT XÓA RIÊNG TỪNG LẦN)
    private fun loadFuelHistory() {
        val list = dbHelper.getAllFuelLogs()
        val adapter = object : ArrayAdapter<FuelRefill>(this, 0, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_fuel, parent, false)
                val item = getItem(position)!!

                view.findViewById<TextView>(R.id.fuel_datetime).text = "${item.date} ${item.time}"
                view.findViewById<TextView>(R.id.fuel_amount).text = String.format(Locale.US, "%,d đ", item.amountPaid)
                view.findViewById<TextView>(R.id.fuel_stats_km).text = String.format(Locale.US, "Chạy được: %.1f km (%.2f L)", item.kmSinceLast, item.liters)
                view.findViewById<TextView>(R.id.fuel_cost_km).text = String.format(Locale.US, "~ %,.0f đ/km", item.costPerKm)

                // NÚT SỬA LẦN ĐỔ XĂNG
                view.findViewById<ImageButton>(R.id.btn_edit_fuel).setOnClickListener {
                    showEditFuelDialog(item)
                }

                // NÚT XÓA RIÊNG TỪNG LẦN ĐỔ XĂNG
                view.findViewById<ImageButton>(R.id.btn_delete_fuel).setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle("Xóa lần đổ xăng này?")
                        .setMessage("Số tiền: ${String.format(Locale.US, "%,d đ", item.amountPaid)} vào lúc ${item.date} ${item.time}")
                        .setPositiveButton("Xóa") { _, _ ->
                            dbHelper.deleteFuelById(item.id)
                            loadFuelHistory()
                            Toast.makeText(context, "Đã xóa lần đổ xăng này!", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Hủy", null)
                        .show()
                }

                return view
            }
        }
        listFuelHistory.adapter = adapter
    }


    private fun showEditShiftDialog(shift: Shift) {
        val scrollView = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
            setBackgroundColor(android.graphics.Color.parseColor("#161C24"))
        }
        scrollView.addView(layout)

        fun createLabel(text: String): TextView {
            return TextView(this).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                textSize = 12f
                setPadding(0, 16, 0, 6)
            }
        }

        fun createInput(initialText: String, isNumber: Boolean = false, isDecimal: Boolean = false): EditText {
            return EditText(this).apply {
                setText(initialText)
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                setBackgroundResource(R.drawable.bg_input)
                setPadding(24, 20, 24, 20)
                textSize = 14f
                if (isDecimal) {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                } else if (isNumber) {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
            }
        }

        layout.addView(createLabel("Tên ca chạy"))
        val etName = createInput(shift.shiftName)
        layout.addView(etName)

        layout.addView(createLabel("Quãng đường (km)"))
        val etKm = createInput(String.format(Locale.US, "%.2f", shift.distanceKm), isDecimal = true)
        layout.addView(etKm)

        layout.addView(createLabel("Thời gian chạy (phút)"))
        val etMin = createInput((shift.durationSeconds / 60).toString(), isNumber = true)
        layout.addView(etMin)

        layout.addView(createLabel("Doanh thu cuốc xe (VNĐ)"))
        val etRev = createInput(shift.revenue.toString(), isNumber = true)
        layout.addView(etRev)

        layout.addView(createLabel("Tiền xăng (VNĐ)"))
        val etFuel = createInput(shift.fuelCost.toString(), isNumber = true)
        layout.addView(etFuel)

        layout.addView(createLabel("Chi phí khác (VNĐ)"))
        val etOther = createInput(shift.otherCost.toString(), isNumber = true)
        layout.addView(etOther)

        AlertDialog.Builder(this)
            .setTitle("✏️ Chỉnh sửa " + shift.shiftName)
            .setView(scrollView)
            .setPositiveButton("Cập nhật") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { shift.shiftName }
                val km = etKm.text.toString().toDoubleOrNull() ?: shift.distanceKm
                val min = etMin.text.toString().toLongOrNull() ?: (shift.durationSeconds / 60)
                val rev = etRev.text.toString().toLongOrNull() ?: 0L
                val fuel = etFuel.text.toString().toLongOrNull() ?: 0L
                val other = etOther.text.toString().toLongOrNull() ?: 0L
                val net = rev - fuel - other
                val sec = min * 60

                val updated = shift.copy(
                    shiftName = name,
                    distanceKm = km,
                    durationSeconds = sec,
                    revenue = rev,
                    fuelCost = fuel,
                    otherCost = other,
                    netProfit = net
                )
                dbHelper.updateShift(updated)
                refreshTodayCenterMetrics()
                loadDailyHistory()
                Toast.makeText(this, "Đã cập nhật $name thành công!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showEditFuelDialog(item: FuelRefill) {
        val scrollView = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
            setBackgroundColor(android.graphics.Color.parseColor("#161C24"))
        }
        scrollView.addView(layout)

        fun createLabel(text: String): TextView {
            return TextView(this).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                textSize = 12f
                setPadding(0, 16, 0, 6)
            }
        }

        fun createInput(initialText: String, isNumber: Boolean = false, isDecimal: Boolean = false): EditText {
            return EditText(this).apply {
                setText(initialText)
                setTextColor(ContextCompat.getColor(context, R.color.text_main))
                setBackgroundResource(R.drawable.bg_input)
                setPadding(24, 20, 24, 20)
                textSize = 14f
                if (isDecimal) {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                } else if (isNumber) {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
            }
        }

        layout.addView(createLabel("Ngày đổ (yyyy-MM-dd)"))
        val etDate = createInput(item.date)
        layout.addView(etDate)

        layout.addView(createLabel("Giờ đổ (HH:mm:ss)"))
        val etTime = createInput(item.time)
        layout.addView(etTime)

        layout.addView(createLabel("Số tiền đổ xăng (VNĐ)"))
        val etAmount = createInput(item.amountPaid.toString(), isNumber = true)
        layout.addView(etAmount)

        layout.addView(createLabel("Giá xăng tại trạm (VNĐ/lít)"))
        val etPrice = createInput(item.fuelPrice.toString(), isNumber = true)
        layout.addView(etPrice)

        layout.addView(createLabel("Số km chạy được từ lần trước (km)"))
        val etKmSince = createInput(String.format(Locale.US, "%.1f", item.kmSinceLast), isDecimal = true)
        layout.addView(etKmSince)

        AlertDialog.Builder(this)
            .setTitle("✏️ Sửa lần đổ xăng")
            .setView(scrollView)
            .setPositiveButton("Cập nhật") { _, _ ->
                val date = etDate.text.toString().trim().ifEmpty { item.date }
                val time = etTime.text.toString().trim().ifEmpty { item.time }
                val amount = etAmount.text.toString().toLongOrNull() ?: item.amountPaid
                val price = etPrice.text.toString().toLongOrNull() ?: item.fuelPrice
                val kmSince = etKmSince.text.toString().toDoubleOrNull() ?: item.kmSinceLast

                dbHelper.updateFuelRefill(item.id, date, time, amount, price, kmSince)
                loadFuelHistory()
                Toast.makeText(this, "Đã cập nhật lần đổ xăng thành công!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun formatSeconds(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("⚡ Cài đặt cho Android 15")
                    .setMessage("Để ứng dụng đo km ổn định khi bạn tắt màn hình hoặc bỏ điện thoại trong túi, hãy chọn 'Không tối ưu hóa' (Unrestricted) cho ứng dụng.")
                    .setPositiveButton("Cài đặt ngay") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {}
                    }
                    .setNegativeButton("Để sau", null)
                    .show()
            }
        }
    }
}
