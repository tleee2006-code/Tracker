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

class MainActivity : AppCompatActivity() {

    private lateinit var dbHelper: DatabaseHelper

    // UI Elements
    private lateinit var tvTotalOdometer: TextView
    private lateinit var tvShiftTitle: TextView
    private lateinit var tvShiftKm: TextView
    private lateinit var tvShiftTime: TextView
    private lateinit var tvShiftSpeed: TextView

    private lateinit var btnStartShift: Button
    private lateinit var layoutRunningControls: LinearLayout
    private lateinit var btnPauseShift: Button
    private lateinit var btnStopShift: Button
    private lateinit var btnQuickRefill: Button

    private lateinit var viewTracker: View
    private lateinit var viewHistory: View
    private lateinit var viewFuel: View

    private lateinit var listDailyHistory: ListView
    private lateinit var listFuelHistory: ListView

    private lateinit var btnTabTracker: Button
    private lateinit var btnTabHistory: Button
    private lateinit var btnTabFuel: Button

    private var activeShiftStartTime: String = ""
    private var activeShiftName: String = "Ca 1"

    // Broadcast nhận dữ liệu thời gian thực từ GPS Service
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GpsTrackingService.BROADCAST_LOCATION_UPDATE) {
                val km = intent.getDoubleExtra(GpsTrackingService.EXTRA_DISTANCE_KM, 0.0)
                val speed = intent.getFloatExtra(GpsTrackingService.EXTRA_SPEED_KMH, 0f)
                val seconds = intent.getLongExtra(GpsTrackingService.EXTRA_DURATION_SEC, 0L)

                tvShiftKm.text = String.format(Locale.US, "%.2f", km)
                tvShiftSpeed.text = String.format(Locale.US, "%.0f km/h", speed)

                val h = seconds / 3600
                val m = (seconds % 3600) / 60
                val s = seconds % 60
                tvShiftTime.text = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
            }
        }
    }

    // Xin quyền cho Android 14/15
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (!fineLocationGranted) {
            Toast.makeText(this, "Cần cấp quyền vị trí để đo quãng đường!", Toast.LENGTH_LONG).show()
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
        updateOdometerDisplay()
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
        updateOdometerDisplay()
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
        tvShiftTitle = findViewById(R.id.tv_shift_title)
        tvShiftKm = findViewById(R.id.tv_shift_km)
        tvShiftTime = findViewById(R.id.tv_shift_time)
        tvShiftSpeed = findViewById(R.id.tv_shift_speed)

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

        btnTabTracker = findViewById(R.id.btn_tab_tracker)
        btnTabHistory = findViewById(R.id.btn_tab_history)
        btnTabFuel = findViewById(R.id.btn_tab_fuel)
    }

    private fun setupListeners() {
        // Tab Navigation
        btnTabTracker.setOnClickListener { switchTab(1) }
        btnTabHistory.setOnClickListener { 
            switchTab(2)
            loadDailyHistory()
        }
        btnTabFuel.setOnClickListener { 
            switchTab(3)
            loadFuelHistory()
        }

        // Bắt đầu ca
        btnStartShift.setOnClickListener {
            showStartShiftDialog()
        }

        // Tạm dừng ca
        btnPauseShift.setOnClickListener {
            if (GpsTrackingService.isPaused) {
                val intent = Intent(this, GpsTrackingService::class.java).apply {
                    action = GpsTrackingService.ACTION_RESUME
                }
                startService(intent)
                btnPauseShift.text = "⏸ Tạm dừng"
            } else {
                val intent = Intent(this, GpsTrackingService::class.java).apply {
                    action = GpsTrackingService.ACTION_PAUSE
                }
                startService(intent)
                btnPauseShift.text = "▶ Tiếp tục"
            }
        }

        // Kết thúc ca
        btnStopShift.setOnClickListener {
            showEndShiftDialog()
        }

        // Đổ xăng
        btnQuickRefill.setOnClickListener {
            showRefillDialog()
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

    private fun updateOdometerDisplay() {
        val odo = dbHelper.getTotalOdometer()
        tvTotalOdometer.text = String.format(Locale.US, "Tổng: %.1f km", odo)
    }

    private fun syncServiceState() {
        if (GpsTrackingService.isRunning) {
            btnStartShift.visibility = View.GONE
            layoutRunningControls.visibility = View.VISIBLE
            tvShiftTitle.text = "ĐANG CHẠY: $activeShiftName"
            btnPauseShift.text = if (GpsTrackingService.isPaused) "▶ Tiếp tục" else "⏸ Tạm dừng"
            tvShiftKm.text = String.format(Locale.US, "%.2f", GpsTrackingService.currentShiftKm)
        } else {
            btnStartShift.visibility = View.VISIBLE
            layoutRunningControls.visibility = View.GONE
            tvShiftTitle.text = "CHƯA CÓ CA NÀO ĐANG CHẠY"
            tvShiftKm.text = "0.00"
            tvShiftTime.text = "00:00:00"
            tvShiftSpeed.text = "0 km/h"
        }
    }

    // Dialog Bắt Đầu Ca (Hỗ trợ nhiều ca trong ngày)
    private fun showStartShiftDialog() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val count = dbHelper.getTodayShiftCount(todayStr)
        val suggestedShiftName = "Ca ${count + 1} (" + when (count) {
            0 -> "Sáng"
            1 -> "Chiều"
            2 -> "Tối"
            else -> "Đêm"
        } + ")"

        val input = EditText(this).apply {
            setText(suggestedShiftName)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("▶ Bắt đầu ca mới")
            .setMessage("Hôm nay bạn đã chạy $count ca. Nhập tên cho ca này:")
            .setView(input)
            .setPositiveButton("Bật GPS & Bắt đầu") { _, _ ->
                activeShiftName = input.text.toString().trim().ifEmpty { suggestedShiftName }
                activeShiftStartTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

                val serviceIntent = Intent(this, GpsTrackingService::class.java).apply {
                    action = GpsTrackingService.ACTION_START
                    putExtra(GpsTrackingService.EXTRA_SHIFT_NAME, activeShiftName)
                }

                // Khởi động Foreground Service chuẩn Android 14/15
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                syncServiceState()
                Toast.makeText(this, "Đã kích hoạt định vị ngầm cho $activeShiftName!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // Dialog Kết Thúc Ca & Tính Lợi Nhuận
    private fun showEndShiftDialog() {
        val distance = GpsTrackingService.currentShiftKm
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val etRevenue = EditText(this).apply { hint = "Tổng doanh thu app ship (VNĐ)" }
        val etFuelCost = EditText(this).apply { 
            hint = "Tiền xăng tính cho ca (VNĐ)" 
            // Tự động tính ước lượng xăng: 350đ/km
            val estFuel = (distance * 350).toLong()
            setText(estFuel.toString())
        }
        val etOtherCost = EditText(this).apply { 
            hint = "Chi phí khác: nước, gửi xe (VNĐ)" 
            setText("0")
        }

        layout.addView(TextView(this).apply { 
            text = String.format(Locale.US, "Quãng đường ca vừa chạy: %.2f km", distance)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.accent_blue))
        })
        layout.addView(etRevenue)
        layout.addView(etFuelCost)
        layout.addView(etOtherCost)

        AlertDialog.Builder(this)
            .setTitle("⏹ Kết thúc ca làm việc")
            .setView(layout)
            .setPositiveButton("Lưu Ca & Tắt GPS") { _, _ ->
                val rev = etRevenue.text.toString().toLongOrNull() ?: 0L
                val fuel = etFuelCost.text.toString().toLongOrNull() ?: 0L
                val other = etOtherCost.text.toString().toLongOrNull() ?: 0L
                val net = rev - fuel - other

                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val endTimeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

                // 1. Dừng Service GPS HOÀN TOÀN
                val stopIntent = Intent(this, GpsTrackingService::class.java).apply {
                    action = GpsTrackingService.ACTION_STOP
                }
                startService(stopIntent)

                // 2. Lưu vào CSDL
                val shift = Shift(
                    date = todayStr,
                    shiftName = activeShiftName,
                    startTime = activeShiftStartTime,
                    endTime = endTimeStr,
                    distanceKm = distance,
                    revenue = rev,
                    fuelCost = fuel,
                    otherCost = other,
                    netProfit = net
                )
                dbHelper.insertShift(shift)

                updateOdometerDisplay()
                syncServiceState()

                val msg = String.format(
                    Locale.US,
                    "Ca hoàn tất!\nQuãng đường: %.2f km\nLợi nhuận ròng: %,d VNĐ\nGPS đã tắt 100%%.",
                    distance, net
                )
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
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
                    "Đã lưu!\nKm từ lần trước: %.1f km\nChi phí: %,.0f đ/km",
                    refill.kmSinceLast, refill.costPerKm
                )
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // Tải danh sách theo Ngày & Chi tiết từng ca
    private fun loadDailyHistory() {
        val list = dbHelper.getDailySummaries()
        val adapter = object : ArrayAdapter<DailySummary>(this, 0, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_daily_summary, parent, false)
                val item = getItem(position)!!

                view.findViewById<TextView>(R.id.item_date).text = item.date
                view.findViewById<TextView>(R.id.item_shift_count).text = "${item.shiftCount} ca chạy"
                view.findViewById<TextView>(R.id.item_total_km).text = String.format(Locale.US, "%.1f km", item.totalDistanceKm)
                view.findViewById<TextView>(R.id.item_total_rev).text = String.format(Locale.US, "%,d đ", item.totalRevenue)
                view.findViewById<TextView>(R.id.item_total_profit).text = String.format(Locale.US, "+%,d đ", item.totalProfit)

                // Khi bấm vào một ngày -> Hiển thị chi tiết từng ca của ngày đó
                view.setOnClickListener {
                    showShiftsOfDayDialog(item.date)
                }

                return view
            }
        }
        listDailyHistory.adapter = adapter
    }

    // Dialog bung ra chi tiết các ca của một ngày
    private fun showShiftsOfDayDialog(dateStr: String) {
        val shifts = dbHelper.getShiftsByDate(dateStr)
        val sb = StringBuilder()
        var totalDist = 0.0
        var totalNet = 0L

        shifts.forEachIndexed { index, s ->
            totalDist += s.distanceKm
            totalNet += s.netProfit
            sb.append("🔹 ${s.shiftName} (${s.startTime} - ${s.endTime}):\n")
            sb.append("   • Quãng đường: ${String.format(Locale.US, "%.2f", s.distanceKm)} km\n")
            sb.append("   • Thu: ${String.format(Locale.US, "%,d", s.revenue)} đ | Xăng: ${String.format(Locale.US, "%,d", s.fuelCost)} đ\n")
            sb.append("   • Lời ròng: ${String.format(Locale.US, "%,d", s.netProfit)} đ\n\n")
        }

        sb.append("========================\n")
        sb.append("🏆 TỔNG CẢ NGÀY: ${String.format(Locale.US, "%.1f", totalDist)} km | Lời: ${String.format(Locale.US, "%,d", totalNet)} đ")

        AlertDialog.Builder(this)
            .setTitle("Chi tiết các ca ngày $dateStr")
            .setMessage(sb.toString())
            .setPositiveButton("Đóng", null)
            .show()
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

    // Hướng dẫn tắt tối ưu hóa pin cho Android 15 để không bị tắt ngầm
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
