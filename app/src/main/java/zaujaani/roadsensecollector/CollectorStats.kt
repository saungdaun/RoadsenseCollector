package zaujaani.roadsensecollector

import android.content.Context

object CollectorStats {

    private const val PREFS_NAME = "collector_stats"

    fun increment(context: Context, classCode: String, count: Int = 1) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getInt(classCode, 0)
        prefs.edit().putInt(classCode, current + count).apply()
    }

    fun getCount(context: Context, classCode: String): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(classCode, 0)
    }

    fun getTotal(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return RoadClasses.ALL_CLASSES.sumOf { prefs.getInt(it.code, 0) }
    }

    fun getAllStats(context: Context): Map<String, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return RoadClasses.ALL_CLASSES.associate { it.code to prefs.getInt(it.code, 0) }
    }

    fun recalculateFromDisk(context: Context) {
        val prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        RoadClasses.ALL_CLASSES.forEach { cls ->
            val folder = getClassFolder(context, cls.code)
            val count  = folder?.listFiles()
                ?.count { it.extension.lowercase() == "jpg" } ?: 0
            editor.putInt(cls.code, count)
        }
        editor.apply()
    }

    fun getClassFolder(context: Context, classCode: String): java.io.File? {
        val cls  = RoadClasses.BY_CODE[classCode] ?: return null
        val base = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_PICTURES
        )
        return java.io.File(base, "RoadSenseCollector/${classCode}_${cls.nameId}")
    }
}