package zaujaani.roadsensecollector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object RoadClasses {

    data class RoadClass(
        val code    : String,
        val nameId  : String,
        val category: Category,
        val colorHex: String,
        val isBuiltIn: Boolean = true   // false = class custom buatan user
    )

    enum class Category(val label: String) {
        DISTRESS("Kerusakan Jalan"),
        SURFACE ("Jenis Permukaan"),
        CUSTOM  ("Kelas Custom")
    }

    // ── Default built-in classes ──────────────────────────────────────
    private val BUILT_IN_CLASSES = listOf(
        RoadClass("D00", "Retak Memanjang",  Category.DISTRESS, "#F44336"),
        RoadClass("D01", "Retak Melintang",  Category.DISTRESS, "#E53935"),
        RoadClass("D02", "Retak Buaya",      Category.DISTRESS, "#B71C1C"),
        RoadClass("D03", "Lubang (Pothole)", Category.DISTRESS, "#FF5722"),
        RoadClass("D04", "Alur (Rutting)",   Category.DISTRESS, "#FF7043"),
        RoadClass("D05", "Pengelupasan",     Category.DISTRESS, "#FF9800"),
        RoadClass("D06", "Retak Blok",       Category.DISTRESS, "#FFA000"),
        RoadClass("D07", "Retak Tepi",       Category.DISTRESS, "#E65100"),
        RoadClass("D08", "Tambalan",         Category.DISTRESS, "#FFC107"),
        RoadClass("D09", "Bleeding",         Category.DISTRESS, "#795548"),
        RoadClass("P00", "Aspal",            Category.SURFACE,  "#546E7A"),
        RoadClass("P01", "Beton",            Category.SURFACE,  "#B0BEC5"),
        RoadClass("P02", "Paving Block",     Category.SURFACE,  "#90A4AE"),
        RoadClass("P03", "Kerikil",          Category.SURFACE,  "#A1887F"),
        RoadClass("P04", "Tanah",            Category.SURFACE,  "#8D6E63"),
    )

    private const val PREFS_NAME   = "road_classes_prefs"
    private const val KEY_DISABLED = "disabled_codes"   // JSON array of disabled codes
    private const val KEY_CUSTOM   = "custom_classes"   // JSON array of custom class objects

    // ── Load all classes (built-in + custom) ──────────────────────────
    fun getAllClasses(context: Context): List<RoadClass> {
        val custom = loadCustomClasses(context)
        return BUILT_IN_CLASSES + custom
    }

    // ── Active-only (untuk class picker di CollectorActivity, assign, export) ─
    fun getActiveClasses(context: Context): List<RoadClass> {
        val disabled = loadDisabledCodes(context)
        return getAllClasses(context).filter { it.code !in disabled }
    }

    // ── Context-aware lookup: built-in + custom ───────────────────────
    // Gunakan ini sebagai pengganti BY_CODE di semua tempat yang punya context.
    fun byCodeCtx(context: Context, code: String): RoadClass? =
        getAllClasses(context).find { it.code == code }

    // Alias eksplisit — sama dengan byCode() yang sudah ada sebelumnya.
    fun byCode(context: Context, code: String): RoadClass? = byCodeCtx(context, code)

    // ── Deprecated: hanya built-in, JANGAN pakai untuk lookup baru ───
    // Tetap ada supaya kode lama di CollectorStats yang belum difix tidak merah,
    // tapi semua callers sudah diganti ke byCodeCtx / getAllClasses.
    @Deprecated(
        message = "Hanya berisi built-in classes. Gunakan byCodeCtx(context, code).",
        replaceWith = ReplaceWith("byCodeCtx(context, code)")
    )
    val BY_CODE: Map<String, RoadClass> = BUILT_IN_CLASSES.associateBy { it.code }

    // ── Deprecated: hanya built-in, pakai getAllClasses(context) ─────
    @Deprecated(
        message = "Hanya berisi built-in classes. Gunakan getAllClasses(context).",
        replaceWith = ReplaceWith("getAllClasses(context)")
    )
    val ALL_CLASSES: List<RoadClass> get() = BUILT_IN_CLASSES

    fun byCategory(context: Context, category: Category) =
        getAllClasses(context).filter { it.category == category }

    val CATEGORIES = Category.entries.toList()

    // ── Enable / Disable ──────────────────────────────────────────────
    fun isEnabled(context: Context, code: String): Boolean =
        code !in loadDisabledCodes(context)

    fun setEnabled(context: Context, code: String, enabled: Boolean) {
        val disabled = loadDisabledCodes(context).toMutableSet()
        if (enabled) disabled.remove(code) else disabled.add(code)
        saveDisabledCodes(context, disabled)
    }

    fun toggleEnabled(context: Context, code: String) {
        setEnabled(context, code, !isEnabled(context, code))
    }

    // ── Add / Delete custom class ─────────────────────────────────────
    fun addCustomClass(context: Context, code: String, name: String,
                       category: Category, colorHex: String): Boolean {
        if (getAllClasses(context).any { it.code == code }) return false  // duplicate
        val list = loadCustomClasses(context).toMutableList()
        list.add(RoadClass(code, name, category, colorHex, isBuiltIn = false))
        saveCustomClasses(context, list)
        return true
    }

    fun deleteCustomClass(context: Context, code: String): Boolean {
        val list = loadCustomClasses(context).toMutableList()
        val removed = list.removeIf { it.code == code && !it.isBuiltIn }
        if (removed) saveCustomClasses(context, list)
        val disabled = loadDisabledCodes(context).toMutableSet()
        disabled.remove(code)
        saveDisabledCodes(context, disabled)
        return removed
    }

    // ── Persistence helpers ───────────────────────────────────────────
    private fun loadDisabledCodes(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_DISABLED, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun saveDisabledCodes(context: Context, codes: Set<String>) {
        val arr = JSONArray().apply { codes.forEach { put(it) } }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISABLED, arr.toString()).apply()
    }

    private fun loadCustomClasses(context: Context): List<RoadClass> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_CUSTOM, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RoadClass(
                    code      = obj.getString("code"),
                    nameId    = obj.getString("nameId"),
                    category  = Category.valueOf(obj.optString("category", "CUSTOM")),
                    colorHex  = obj.optString("colorHex", "#607D8B"),
                    isBuiltIn = false
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveCustomClasses(context: Context, list: List<RoadClass>) {
        val arr = JSONArray()
        list.filter { !it.isBuiltIn }.forEach { cls ->
            arr.put(JSONObject().apply {
                put("code",     cls.code)
                put("nameId",   cls.nameId)
                put("category", cls.category.name)
                put("colorHex", cls.colorHex)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }
}