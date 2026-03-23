package zaujaani.roadsensecollector

object RoadClasses {

    data class RoadClass(
        val code    : String,
        val nameId  : String,
        val category: Category,
        val colorHex: String
    )

    enum class Category(val label: String) {
        DISTRESS("Kerusakan Jalan"),
        SURFACE ("Jenis Permukaan")
    }

    val ALL_CLASSES = listOf(
        // ── Distress (istilah baku PCI/IRI) ──────────────────────────
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

        // ── Surface ───────────────────────────────────────────────────
        RoadClass("P00", "Aspal",            Category.SURFACE,  "#546E7A"),
        RoadClass("P01", "Beton",            Category.SURFACE,  "#B0BEC5"),
        RoadClass("P02", "Paving Block",     Category.SURFACE,  "#90A4AE"),
        RoadClass("P03", "Kerikil",          Category.SURFACE,  "#A1887F"),
        RoadClass("P04", "Tanah",            Category.SURFACE,  "#8D6E63"),
    )

    val BY_CODE: Map<String, RoadClass> = ALL_CLASSES.associateBy { it.code }

    fun byCategory(category: Category) = ALL_CLASSES.filter { it.category == category }

    val CATEGORIES = Category.entries.toList()
}