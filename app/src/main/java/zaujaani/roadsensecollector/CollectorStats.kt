package zaujaani.roadsensecollector

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object CollectorStats {

    private const val PREFS_NAME = "collector_stats"

    // ── GPS ───────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun getLastLocation(context: Context): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            providers.mapNotNull { provider ->
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }
        } catch (e: Exception) { null }
    }

    // ── GPS JSON sidecar ──────────────────────────────────────────────
    // Fix: pakai byCodeCtx supaya kelas custom juga dapat GPS JSON
    fun saveGpsJson(
        context: Context,
        classCode: String,
        photoFilename: String,
        location: Location?
    ) {
        try {
            val cls    = RoadClasses.byCodeCtx(context, classCode) ?: return
            val folder = getClassFolder(context, classCode)         ?: return
            folder.mkdirs()

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(Date())

            val json = JSONObject().apply {
                put("class_code",  classCode)
                put("class_name",  cls.nameId)
                put("category",    cls.category.label)
                put("photo_file",  photoFilename)
                put("timestamp",   timestamp)
                put("latitude",    location?.latitude  ?: 0.0)
                put("longitude",   location?.longitude ?: 0.0)
                put("accuracy_m",  location?.accuracy  ?: -1f)
                put("gps_valid",   location != null)
            }

            val jsonFile = File(folder,
                photoFilename.replace(".jpg",  ".json")
                    .replace(".jpeg", ".json")
                    .replace(".png",  ".json"))
            jsonFile.writeText(json.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────
    fun increment(context: Context, classCode: String, count: Int = 1) {
        val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(classCode, 0)
        prefs.edit().putInt(classCode, current + count).apply()
    }

    fun getCount(context: Context, classCode: String): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(classCode, 0)
    }

    // Fix: hitung semua kelas (built-in + custom)
    fun getTotal(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return RoadClasses.getAllClasses(context).sumOf { prefs.getInt(it.code, 0) }
    }

    // Fix: kembalikan stats semua kelas (built-in + custom)
    fun getAllStats(context: Context): Map<String, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return RoadClasses.getAllClasses(context).associate { it.code to prefs.getInt(it.code, 0) }
    }

    // Fix: recalculate dari disk untuk semua kelas (built-in + custom)
    fun recalculateFromDisk(context: Context) {
        val prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        RoadClasses.getAllClasses(context).forEach { cls ->
            val folder = getClassFolder(context, cls.code)
            val count  = folder?.listFiles()
                ?.count { it.extension.lowercase() == "jpg" } ?: 0
            editor.putInt(cls.code, count)
        }
        editor.apply()
    }

    // Fix: pakai byCodeCtx supaya folder kelas custom juga ditemukan
    fun getClassFolder(context: Context, classCode: String): File? {
        val cls  = RoadClasses.byCodeCtx(context, classCode) ?: return null
        val base = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_PICTURES
        )
        return File(base, "RoadSenseCollector/${classCode}_${cls.nameId}")
    }
}