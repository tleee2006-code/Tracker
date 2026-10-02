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
    val date: String,             // yyyy-MM-dd
    val shiftName: String,        // "Ca 1 (Sáng)"
    val startTime: String,        // "07:30:15"
    val endTime: String,          // "11:45:20"
    val durationSeconds: Long,    // Thời lượng chạy tính đến từng giây
    val distanceKm: Double,       // km
    val revenue: Long,            // Doanh thu (VNĐ)
    val fuelCost: Long,           // Tiền xăng (VNĐ)
    val otherCost: Long,          // Chi phí khác (VNĐ)
    val netProfit: Long           // Lợi nhuận ròng (VNĐ)
)

data class DailySummary(
    val date: String,
    val shiftCount: Int,
    val totalDistanceKm: Double,
    val totalDurationSeconds: Long,
    val totalRevenue: Long,
    val totalFuelCost: Long,
    val totalProfit: Long
)

data class FuelRefill(
    val id: Long = 0,
    val date: String,
    val time: String,
    val amountPaid: Long,
    val fuelPrice: Long,
    val liters: Double,
    val totalAppKm: Double,
    val kmSinceLast: Double,
    val costPerKm: Double
)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "shipper_tracker.db"
        const val DATABASE_VERSION = 3

        const val TABLE_SHIFTS = "shifts"
        const val TABLE_FUEL = "fuel_refills"
        const val TABLE_STATE = "app_state"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SHIFTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT NOT NULL,
                shift_name TEXT NOT NULL,
                start_time TEXT NOT NULL,
                end_time TEXT NOT NULL,
                duration_seconds INTEGER DEFAULT 0,
                distance_km REAL NOT NULL,
                revenue INTEGER NOT NULL,
                fuel_cost INTEGER NOT NULL,
                other_cost INTEGER NOT NULL,
                net_profit INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_FUEL (
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

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_STATE (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("INSERT OR IGNORE INTO $TABLE_STATE (key, value) VALUES ('total_odometer_km', '0.0')")
        db.execSQL("INSERT OR IGNORE INTO $TABLE_STATE (key, value) VALUES ('last_refill_km', '0.0')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        try {
            db.execSQL("ALTER TABLE $TABLE_SHIFTS ADD COLUMN duration_seconds INTEGER DEFAULT 0")
        } catch (e: Exception) {}
    }

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

    fun insertShift(shift: Shift): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("date", shift.date)
            put("shift_name", shift.shiftName)
            put("start_time", shift.startTime)
            put("end_time", shift.endTime)
            put("duration_seconds", shift.durationSeconds)
            put("distance_km", shift.distanceKm)
            put("revenue", shift.revenue)
            put("fuel_cost", shift.fuelCost)
            put("other_cost", shift.otherCost)
            put("net_profit", shift.netProfit)
        }
        val id = db.insert(TABLE_SHIFTS, null, cv)
        val currentOdo = getTotalOdometer()
        setTotalOdometer(currentOdo + shift.distanceKm)
        return id
    }

    fun getTodayStats(todayStr: String): Triple<Double, Long, Int> {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT SUM(distance_km), SUM(duration_seconds), COUNT(*) 
            FROM $TABLE_SHIFTS 
            WHERE date = ?
        """.trimIndent(), arrayOf(todayStr))
        
        var totalKm = 0.0
        var totalSec = 0L
        var count = 0
        if (cursor.moveToFirst()) {
            totalKm = cursor.getDouble(0)
            totalSec = cursor.getLong(1)
            count = cursor.getInt(2)
        }
        cursor.close()
        return Triple(totalKm, totalSec, count)
    }

    fun getDailySummaries(): List<DailySummary> {
        val list = mutableListOf<DailySummary>()
        val db = readableDatabase
        val query = """
            SELECT 
                date,
                COUNT(*) as shift_count,
                SUM(distance_km) as total_dist,
                SUM(duration_seconds) as total_duration,
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
                    totalDurationSeconds = cursor.getLong(3),
                    totalRevenue = cursor.getLong(4),
                    totalFuelCost = cursor.getLong(5),
                    totalProfit = cursor.getLong(6)
                )
            )
        }
        cursor.close()
        return list
    }

    fun getShiftsByDate(dateStr: String): List<Shift> {
        val list = mutableListOf<Shift>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_SHIFTS WHERE date = ? ORDER BY id ASC",
            arrayOf(dateStr)
        )
        while (cursor.moveToNext()) {
            val dur = try { cursor.getLong(cursor.getColumnIndexOrThrow("duration_seconds")) } catch(e: Exception) { 0L }
            list.add(
                Shift(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    date = cursor.getString(cursor.getColumnIndexOrThrow("date")),
                    shiftName = cursor.getString(cursor.getColumnIndexOrThrow("shift_name")),
                    startTime = cursor.getString(cursor.getColumnIndexOrThrow("start_time")),
                    endTime = cursor.getString(cursor.getColumnIndexOrThrow("end_time")),
                    durationSeconds = dur,
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

    fun deleteShiftsByDate(dateStr: String) {
        val db = writableDatabase
        db.delete(TABLE_SHIFTS, "date = ?", arrayOf(dateStr))
        recalculateOdometer()
    }

    fun deleteShiftById(id: Long) {
        val db = writableDatabase
        db.delete(TABLE_SHIFTS, "id = ?", arrayOf(id.toString()))
        recalculateOdometer()
    }

    fun clearAllData() {
        val db = writableDatabase
        db.delete(TABLE_SHIFTS, null, null)
        db.delete(TABLE_FUEL, null, null)
        setTotalOdometer(0.0)
        val cv = ContentValues().apply { put("value", "0.0") }
        db.update(TABLE_STATE, cv, "key = 'last_refill_km'", null)
    }

    private fun recalculateOdometer() {
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT SUM(distance_km) FROM $TABLE_SHIFTS", null)
        var total = 0.0
        if (cursor.moveToFirst()) {
            total = cursor.getDouble(0)
        }
        cursor.close()
        setTotalOdometer(total)
    }

    fun recordFuelRefill(amount: Long, price: Long): FuelRefill {
        val db = writableDatabase
        val currentOdo = getTotalOdometer()

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
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)

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
