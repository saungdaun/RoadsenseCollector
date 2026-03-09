package zaujaani.roadsensecollector

object RoadClasses {

    data class RoadClass(
        val code: String,
        val nameId: String,
        val category: Category,
        val colorHex: String
    )

    enum class Category(val label: String) {
        DISTRESS("Kerusakan Jalan"),
        MARKING("Marka Jalan"),
        SIGN("Rambu"),
        ROAD_FEATURE("Fitur Jalan"),
        SURFACE("Jenis Permukaan")
    }

    val ALL_CLASSES = listOf(
        // Distress
        RoadClass("D00", "Retak Memanjang",       Category.DISTRESS,      "#F44336"),
        RoadClass("D01", "Retak Melintang",       Category.DISTRESS,      "#E53935"),
        RoadClass("D02", "Retak Buaya",           Category.DISTRESS,      "#B71C1C"),
        RoadClass("D03", "Lubang (Pothole)",      Category.DISTRESS,      "#FF5722"),
        RoadClass("D04", "Alur (Rutting)",        Category.DISTRESS,      "#FF7043"),
        RoadClass("D05", "Pengelupasan",          Category.DISTRESS,      "#FF9800"),
        RoadClass("D06", "Retak Blok",            Category.DISTRESS,      "#FFA000"),
        RoadClass("D07", "Retak Tepi",            Category.DISTRESS,      "#E65100"),
        RoadClass("D08", "Tambalan",              Category.DISTRESS,      "#FFC107"),
        RoadClass("D09", "Bleeding",              Category.DISTRESS,      "#795548"),
        // Marking
        RoadClass("M00", "Marka Lajur (Baik)",    Category.MARKING,       "#2196F3"),
        RoadClass("M01", "Marka Lajur (Pudar)",   Category.MARKING,       "#64B5F6"),
        RoadClass("M02", "Zebra Cross (Baik)",    Category.MARKING,       "#00BCD4"),
        RoadClass("M03", "Zebra Cross (Pudar)",   Category.MARKING,       "#80DEEA"),
        RoadClass("M04", "Marka Panah",           Category.MARKING,       "#03A9F4"),
        RoadClass("M05", "Garis Stop",            Category.MARKING,       "#0288D1"),
        // Sign
        RoadClass("S00", "Rambu Kecepatan",       Category.SIGN,          "#4CAF50"),
        RoadClass("S01", "Rambu Peringatan",      Category.SIGN,          "#388E3C"),
        RoadClass("S02", "Rambu Larangan",        Category.SIGN,          "#8BC34A"),
        RoadClass("S03", "Rambu Petunjuk",        Category.SIGN,          "#558B2F"),
        RoadClass("S04", "Rambu Rusak",           Category.SIGN,          "#F9A825"),
        RoadClass("S05", "Rambu Hilang",          Category.SIGN,          "#E53935"),
        // Road Feature
        RoadClass("R00", "Penutup Manhole",       Category.ROAD_FEATURE,  "#9E9E9E"),
        RoadClass("R01", "Speed Bump",            Category.ROAD_FEATURE,  "#607D8B"),
        RoadClass("R02", "Tutup Selokan",         Category.ROAD_FEATURE,  "#78909C"),
        RoadClass("R03", "Kerusakan Trotoar",     Category.ROAD_FEATURE,  "#9C27B0"),
        RoadClass("R04", "Kerusakan Bahu Jalan",  Category.ROAD_FEATURE,  "#7B1FA2"),
        RoadClass("R05", "Bekas Galian",          Category.ROAD_FEATURE,  "#673AB7"),
        // Surface
        RoadClass("P00", "Aspal Baik",            Category.SURFACE,       "#37474F"),
        RoadClass("P01", "Aspal Rusak",           Category.SURFACE,       "#546E7A"),
        RoadClass("P02", "Beton",                 Category.SURFACE,       "#B0BEC5"),
        RoadClass("P03", "Paving Block",          Category.SURFACE,       "#90A4AE"),
        RoadClass("P04", "Kerikil",               Category.SURFACE,       "#A1887F"),
        RoadClass("P05", "Tanah",                 Category.SURFACE,       "#8D6E63"),
        RoadClass("P06", "Tanah Berlubang",       Category.SURFACE,       "#6D4C41"),
    )

    val BY_CODE: Map<String, RoadClass> = ALL_CLASSES.associateBy { it.code }

    fun byCategory(category: Category) = ALL_CLASSES.filter { it.category == category }

    val CATEGORIES = Category.values().toList()
}