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
import com.shipper.tracker.service.GpsTrackingService
import java.text.SimpleDateFormat
import java.util.Date
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

    private lateinit var btnStartShift: Button
    private lateinit var layoutRunningControls: LinearLayout
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

    private lateinit var btnTabTracker: Button
    private lateinit var btnTabHistory: Button
    private lateinit var btnTabFuel: Button

    private var activeShiftStartTime: String = ""
    private var activeShiftName: String = "Ca 1"
    private var baseTodayKm: Double = 0.0
    private var baseTodaySeconds: Long = 0L

    // Broadcast nhận dữ liệu thời gian thực 1 giây/lần từ GPS Service
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GpsTrackingService.BROADCAST_LOCATION_UPDATE) {
                val km = intent.getDoubleExtra(GpsTrackingService.EXTRA_DISTANCE_KM, 0.0)
                val speed = intent.getFloatExtra(GpsTrackingService.EXTRA_SPEED_KMH, 0f)
                val seconds = intent.getLongExtra(GpsTrackingService.EXTRA_DURATION_SEC, 0L)
                val paused = intent.getBooleanExtra(GpsTrackingService.EXTRA_IS_PAUSED, false)

                // Cập nhật thẻ ca hiện tại
                tvShiftKm.text = String.format(Locale.US, "Ca: %.2f km (%.0f km/h)", km, speed)
                tvShiftTime.text = formatSeconds(seconds)

                // CẬP NHẬT TỔNG KM VÀ TỔNG THỜI GIAN HÔM NAY CHÍNH GIỮA (LŨY KẾ THEO GIÂY)
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
            Toast.makeText(this, "Cần cấp quyền vị trí chính xác để đo quãng đường!", Toast.LENGTH_LONG).show()
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
        refreshTodayCenterMetrics()
        syncServiceState()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(locationReceiver)
        } catch (e: Exception) {}
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

        btnStartShift = findViewById(R.id.btn_start_shift)
        layoutRunningControls = findViewById(R.id.layout_running_controls)
        btnPauseShift = findViewById(R.id.btn_pause_shift)
        btnStopShift = findViewById(R.id.btn_stop_shift)
        btnQuickRefill = findViewById(R.id.btn_quick_refill)

        viewTracker = findViewById(R.id.view_tracker)
        viewHistory = findViewById(R.id.view_history)
        viewFuel = findViewById(R.id.view_fuel)

        listDailyHistory = findViewById(R.id.list_daily_history)
        listFuelHistory = findViewById(R.id.list_fuel_history)
        btnClearAllHistory = findViewById(R.id.btn_clear_all_history)

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

        // BẮT ĐẦU CA: 1 CHẠM DUY NHẤT LÀ CHẠY NGAY (KHÔNG POPUP LẰNG NHẰNG)
        btnStartShift.setOnClickListener {
            startShiftInstantly()
        }

        // TẠM DỪNG / TIẾP TỤC: 1 CHẠM ĐỔI TRẠNG THÁI NGAY
        btnPauseShift.setOnClickListener {
            togglePauseInstantly()
        }

        // KẾT THÚC CA: MỞ FORM DARK THEME ĐẸP MẮT (KHÔNG DÙNG POPUP TRẮNG)
        btnStopShift.setOnClickListener {
            showEndShiftDarkDialog()
        }

        // ĐỔ XĂNG
        btnQuickRefill.setOnClickListener {
            showRefillDialog()
        }

        // XÓA TOÀN BỘ DỮ LIỆU
        btnClearAllHistory.setOnClickListener {
            showClearAllConfirmDialog()
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

    // Cập nhật số KM và Thời gian hôm nay ở trung tâm màn hình
    private fun refreshTodayCenterMetrics() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val dateDisplay = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        tvTodayDateBadge.text = "HÔM NAY: $dateDisplay"

        val stats = dbHelper.getTodayStats(todayStr)
        baseTodayKm = stats.first
        baseTodaySeconds = stats.second
        val completedCount = stats.third

        tvTodayTotalKm.text = String.format(Locale.US, "%.2f", baseTodayKm)
        tvTodayTotalDuration.text = formatSeconds(baseTodaySeconds)
        tvTodayShiftCompleted.text = "$completedCount ca xong"

        val totalOdo = dbHelper.getTotalOdometer()
        tvTotalOdometer.text = String.format(Locale.US, "Odo tổng: %.1f km", totalOdo)

        // Cập nhật tên ca tiếp theo trên nút bắt đầu
        if (!GpsTrackingService.isRunning) {
            btnStartShift.text = "▶ BẮT ĐẦU CA MỚI (Ca ${completedCount + 1})"
        }
    }

    private fun syncServiceState() {
        if (GpsTrackingService.isRunning) {
            btnStartShift.visibility = View.GONE
            layoutRunningControls.visibility = View.VISIBLE
            cardActiveShift.visibility = View.VISIBLE

            tvActiveShiftTitle.text = if (GpsTrackingService.isPaused) "⏸ ĐÃ TẠM DỪNG: $activeShiftName" else "● ĐANG BẬT GPS: $activeShiftName"
            btnPauseShift.text = if (GpsTrackingService.isPaused) "▶ TIẾP TỤC" else "⏸ TẠM DỪNG"
            tvShiftKm.text = String.format(Locale.US, "Ca: %.2f km", GpsTrackingService.currentShiftKm)
            tvShiftTime.text = formatSeconds(GpsTrackingService.currentShiftSeconds)
        } else {
            btnStartShift.visibility = View.VISIBLE
            layoutRunningControls.visibility = View.GONE
            cardActiveShift.visibility = View.GONE
        }
    }

    // 1 CHẠM BẮT ĐẦU CA NGAY LẬP TỨC
    private fun startShiftInstantly() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val count = dbHelper.getTodayStats(todayStr).third
activeShiftName = "Ca ${count + 1}"
        activeShiftStartTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        // Đổi giao diện 1 chạm ngay tức thì
        GpsTrackingService.isRunning = true
        GpsTrackingService.isPaused = false
        btnStartShift.visibility = View.GONE
        layoutRunningControls.visibility = View.VISIBLE
        cardActiveShift.visibility = View.VISIBLE
        btnPauseShift.text = "⏸ TẠM DỪNG"
        tvActiveShiftTitle.text = "● ĐANG BẬT GPS: $activeShiftName"
        tvShiftKm.text = "Ca: 0.00 km"
        tvShiftTime.text = "00:00:00"

        val serviceIntent = Intent(this, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_START
            putExtra(GpsTrackingService.EXTRA_SHIFT_NAME, activeShiftName)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "Đã bật định vị ngầm 1s/lần cho $activeShiftName!", Toast.LENGTH_SHORT).show()
    }

    // 1 CHẠM TẠM DỪNG / TIẾP TỤC
    private fun togglePauseInstantly() {
        if (GpsTrackingService.isPaused) {
            GpsTrackingService.isPaused = false
            val intent = Intent(this, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_RESUME
            }
            startService(intent)
            btnPauseShift.text = "⏸ TẠM DỪNG"
            tvActiveShiftTitle.text = "● ĐANG BẬT GPS: $activeShiftName"
        } else {
            GpsTrackingService.isPaused = true
            val intent = Intent(this, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_PAUSE
            }
            startService(intent)
            btnPauseShift.text = "▶ TIẾP TỤC"
            tvActiveShiftTitle.text = "⏸ ĐÃ TẠM DỪNG: $activeShiftName"
        }
    }

    // FORM KẾT THÚC CA DARK THEME (KHÔNG DÙNG POPUP TRẮNG)
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

            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val endTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            // DỪNG SERVICE GPS NGAY
            val stopIntent = Intent(this, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_STOP
            }
            startService(stopIntent)

            // ĐỔI TRẠNG THÁI GIAO DIỆN NGAY
            GpsTrackingService.isRunning = false
            GpsTrackingService.isPaused = false
            btnStartShift.visibility = View.VISIBLE
            layoutRunningControls.visibility = View.GONE
            cardActiveShift.visibility = View.GONE

            // LƯU CƠ SỞ DỮ LIỆU
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

    // Xóa tất cả dữ liệu
    private fun showClearAllConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Xác nhận xóa sạch")
            .setMessage("Bạn có chắc chắn muốn xóa toàn bộ lịch sử ca chạy và số liệu đổ xăng? Odometer sẽ trở về 0.0 km.")
            .setPositiveButton("XÓA TẤT CẢ") { _, _ ->
                dbHelper.clearAllData()
                refreshTodayCenterMetrics()
                loadDailyHistory()
                loadFuelHistory()
                Toast.makeText(this, "Đã xóa toàn bộ dữ liệu!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // Tải danh sách theo Ngày dạng DÒNG CHẢY TRỰC TIẾP TRONG APP (KHÔNG POPUP)
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

                // XÓA RIÊNG TOÀN BỘ CÁC CA CỦA NGÀY NÀY
                btnDeleteDay.setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle("Xóa ngày ${item.date}?")
                        .setMessage("Bạn có chắc muốn xóa tất cả ${item.shiftCount} ca của ngày này?")
                        .setPositiveButton("Xóa") { _, _ ->
                            dbHelper.deleteShiftsByDate(item.date)
                            refreshTodayCenterMetrics()
                            loadDailyHistory()
                            Toast.makeText(context, "Đã xóa ngày ${item.date}", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Hủy", null)
                        .show()
                }

                // CHẠM ĐỂ MỞ RỘNG DÒNG CHẢY TỪNG CA TRỰC TIẾP TRONG APP (KHÔNG DÙNG POPUP TRẮNG)
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

                            // Nút xóa riêng từng ca lẻ
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

    // Tải lịch sử đổ xăng
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

                return view
            }
        }
        listFuelHistory.adapter = adapter
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
