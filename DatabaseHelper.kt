package com.shipper.tracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Shift(
    val id: Long = 0,
    val date: String,             // Định dạng: yyyy-MM-dd (ví dụ: 2026-10-02)
    val shiftName: String,        // Ví dụ: "Ca 1 (Sáng)", "Ca 2 (Chiều)"
    val startTime: String,        // Ví dụ: "07:30"
    val endTime: String,          // Ví dụ: "11:45"
    val distanceKm: Double,       // Quãng đường ca (km)
    val revenue: Long,            // Doanh thu thu được từ app ship (VNĐ)
    val fuelCost: Long,           // Tiền xăng tính cho ca (VNĐ)
    val otherCost: Long,          // Chi phí khác: ăn uống, nước, gửi xe (VNĐ)
    val netProfit: Long           // Lợi nhuận ròng = Doanh thu - Xăng - Chi phí
)

data class DailySummary(
    val date: String,             // yyyy-MM-dd
    val shiftCount: Int,          // Số ca chạy trong ngày
    val totalDistanceKm: Double,  // Tổng km các ca
    val totalRevenue: Long,       // Tổng doanh thu ngày
    val totalFuelCost: Long,      // Tổng tiền xăng ngày
    val totalProfit: Long         // Tổng lợi nhuận ròng ngày
)

data class FuelRefill(
    val id: Long = 0,
    val date: String,
    val time: String,
    val amountPaid: Long,         // Số tiền đổ xăng (VNĐ)
    val fuelPrice: Long,          // Giá xăng lúc đổ (VNĐ/lít)
    val liters: Double,           // Số lít xăng
    val totalAppKm: Double,       // Mốc tổng km lúc bấm đổ xăng
    val kmSinceLast: Double,      // Số km chạy được từ lần đổ trước
    val costPerKm: Double         // Chi phí VNĐ / km
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "shipper_tracker.db"
        const val DATABASE_VERSION = 1

        const val TABLE_SHIFTS = "shifts"
        const val TABLE_FUEL = "fuel_refills"
        const val TABLE_STATE = "app_state"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Bật chế độ Write-Ahead Logging để chống xung đột ghi đĩa khi chạy ngầm
        db.enableWriteAheadLogging()

        // Bảng lưu từng ca chạy riêng biệt
        db.execSQL("""
            CREATE TABLE $TABLE_SHIFTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                shift_name TEXT NOT NULL,
                start_time TEXT NOT NULL,
                end_time TEXT NOT NULL,
                distance_km REAL NOT NULL,
                revenue INTEGER NOT NULL,
                fuel_cost INTEGER NOT NULL,
                other_cost INTEGER NOT NULL,
                net_profit INTEGER NOT NULL
            )
        """.trimIndent())

        // Bảng lưu lịch sử đổ xăng
        db.execSQL("""
            CREATE TABLE $TABLE_FUEL (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                amount_paid INTEGER NOT NULL,
                fuel_price INTEGER NOT NULL,
                liters REAL NOT NULL,
                total_app_km REAL NOT NULL,
                km_since_last REAL NOT NULL,
                cost_per_km REAL NOT NULL
            )
        """.trimIndent())

        // Bảng lưu trạng thái tổng Odometer toàn hệ thống
        db.execSQL("""
            CREATE TABLE $TABLE_STATE (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("INSERT OR IGNORE INTO $TABLE_STATE (key, value) VALUES ('total_odometer_km', '0.0')")
        db.execSQL("INSERT OR IGNORE INTO $TABLE_STATE (key, value) VALUES ('last_refill_km', '0.0')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SHIFTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FUEL")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_STATE")
        onCreate(db)
    }

    // --- Xử lý Odometer Tổng ---
    fun getTotalOdometer(): Double {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT value FROM $TABLE_STATE WHERE key = 'total_odometer_km'", null)
        var total = 0.0
        if (cursor.moveToFirst()) {
            total = cursor.getString(0).toDoubleOrNull() ?: 0.0
        }
        cursor.close()
        return total
    }

    fun setTotalOdometer(newTotal: Double) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("value", newTotal.toString())
        }
        db.update(TABLE_STATE, cv, "key = 'total_odometer_km'", null)
    }

    // --- Lưu Ca Chạy ---
    fun insertShift(shift: Shift): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("date", shift.date)
            put("shift_name", shift.shiftName)
            put("start_time", shift.startTime)
            put("end_time", shift.endTime)
            put("distance_km", shift.distanceKm)
            put("revenue", shift.revenue)
            put("fuel_cost", shift.fuelCost)
            put("other_cost", shift.otherCost)
            put("net_profit", shift.netProfit)
        }
        val id = db.insert(TABLE_SHIFTS, null, cv)
        // Cập nhật Odometer
        val currentOdo = getTotalOdometer()
        setTotalOdometer(currentOdo + shift.distanceKm)
        return id
    }

    // Lấy số ca đã chạy trong ngày hôm nay để tự động đặt tên "Ca 1", "Ca 2"...
    fun getTodayShiftCount(todayStr: String): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SHIFTS WHERE date = ?", arrayOf(todayStr))
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }

    // --- Thống kê danh sách theo từng Ngày (Nhóm nhiều ca/ngày) ---
    fun getDailySummaries(): List<DailySummary> {
        val list = mutableListOf<DailySummary>()
        val db = readableDatabase
        val query = """
            SELECT 
                date,
                COUNT(*) as shift_count,
                SUM(distance_km) as total_dist,
                SUM(revenue) as total_rev,
                SUM(fuel_cost) as total_fuel,
                SUM(net_profit) as total_profit
            FROM $TABLE_SHIFTS
            GROUP BY date
            ORDER BY date DESC
            LIMIT 30
        """.trimIndent()

        val cursor = db.rawQuery(query, null)
        while (cursor.moveToNext()) {
            list.add(
                DailySummary(
                    date = cursor.getString(0),
                    shiftCount = cursor.getInt(1),
                    totalDistanceKm = cursor.getDouble(2),
                    totalRevenue = cursor.getLong(3),
                    totalFuelCost = cursor.getLong(4),
                    totalProfit = cursor.getLong(5)
                )
            )
        }
        cursor.close()
        return list
    }

    // Lấy chi tiết tất cả các ca trong một ngày cụ thể
    fun getShiftsByDate(dateStr: String): List<Shift> {
        val list = mutableListOf<Shift>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_SHIFTS WHERE date = ? ORDER BY id ASC",
            arrayOf(dateStr)
        )
        while (cursor.moveToNext()) {
            list.add(
                Shift(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    date = cursor.getString(cursor.getColumnIndexOrThrow("date")),
                    shiftName = cursor.getString(cursor.getColumnIndexOrThrow("shift_name")),
                    startTime = cursor.getString(cursor.getColumnIndexOrThrow("start_time")),
                    endTime = cursor.getString(cursor.getColumnIndexOrThrow("end_time")),
                    distanceKm = cursor.getDouble(cursor.getColumnIndexOrThrow("distance_km")),
                    revenue = cursor.getLong(cursor.getColumnIndexOrThrow("revenue")),
                    fuelCost = cursor.getLong(cursor.getColumnIndexOrThrow("fuel_cost")),
                    otherCost = cursor.getLong(cursor.getColumnIndexOrThrow("other_cost")),
                    netProfit = cursor.getLong(cursor.getColumnIndexOrThrow("net_profit"))
                )
            )
        }
        cursor.close()
        return list
    }

    // --- Xử lý Đổ Xăng ---
    fun recordFuelRefill(amount: Long, price: Long): FuelRefill {
        val db = writableDatabase
        val currentOdo = getTotalOdometer()

        // Lấy mốc km lần đổ trước
        var lastKm = 0.0
        val cursor = db.rawQuery("SELECT value FROM $TABLE_STATE WHERE key = 'last_refill_km'", null)
        if (cursor.moveToFirst()) {
            lastKm = cursor.getString(0).toDoubleOrNull() ?: 0.0
        }
        cursor.close()

        val kmSince = if (currentOdo > lastKm) currentOdo - lastKm else 0.0
        val costKm = if (kmSince > 0) amount.toDouble() / kmSince else 0.0
        val liters = if (price > 0) amount.toDouble() / price else 0.0

        val now = Date()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)

        val cv = ContentValues().apply {
            put("date", dateStr)
            put("time", timeStr)
            put("amount_paid", amount)
            put("fuel_price", price)
            put("liters", liters)
            put("total_app_km", currentOdo)
            put("km_since_last", kmSince)
            put("cost_per_km", costKm)
        }
        val id = db.insert(TABLE_FUEL, null, cv)

        // Cập nhật mốc mới
        val cvUpdate = ContentValues().apply { put("value", currentOdo.toString()) }
        db.update(TABLE_STATE, cvUpdate, "key = 'last_refill_km'", null)

        return FuelRefill(
            id = id,
            date = dateStr,
            time = timeStr,
            amountPaid = amount,
            fuelPrice = price,
            liters = liters,
            totalAppKm = currentOdo,
            kmSinceLast = kmSince,
            costPerKm = costKm
        )
    }

    fun getAllFuelLogs(): List<FuelRefill> {
        val list = mutableListOf<FuelRefill>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_FUEL ORDER BY id DESC LIMIT 30", null)
        while (cursor.moveToNext()) {
            list.add(
                FuelRefill(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    date = cursor.getString(cursor.getColumnIndexOrThrow("date")),
                    time = cursor.getString(cursor.getColumnIndexOrThrow("time")),
                    amountPaid = cursor.getLong(cursor.getColumnIndexOrThrow("amount_paid")),
                    fuelPrice = cursor.getLong(cursor.getColumnIndexOrThrow("fuel_price")),
                    liters = cursor.getDouble(cursor.getColumnIndexOrThrow("liters")),
                    totalAppKm = cursor.getDouble(cursor.getColumnIndexOrThrow("total_app_km")),
                    kmSinceLast = cursor.getDouble(cursor.getColumnIndexOrThrow("km_since_last")),
                    costPerKm = cursor.getDouble(cursor.getColumnIndexOrThrow("cost_per_km"))
                )
            )
        }
        cursor.close()
        return list
    }
}
