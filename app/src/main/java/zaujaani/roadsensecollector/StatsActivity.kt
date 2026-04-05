package zaujaani.roadsensecollector

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import zaujaani.roadsensecollector.databinding.ActivityStatsBinding

class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener    { finish() }
        binding.btnRefresh.setOnClickListener { loadStats() }

        loadStats()
    }

    private fun loadStats() {
        CollectorStats.recalculateFromDisk(this)
        val stats  = CollectorStats.getAllStats(this)
        val total  = stats.values.sum()
        val maxVal = stats.values.maxOrNull() ?: 1

        binding.tvTotalAll.text  = getString(R.string.stats_total_all, total)

        val minRecommended = 50
        // Fix: getAllClasses(this) supaya kelas custom ikut terhitung dalam readiness
        val allClasses   = RoadClasses.getAllClasses(this)
        val classesReady = stats.count { it.value >= minRecommended }
        binding.tvReadiness.text = getString(
            R.string.stats_readiness,
            minRecommended,
            classesReady,
            allClasses.size
        )

        // Fix: pakai allClasses (built-in + custom) bukan ALL_CLASSES (built-in saja)
        val statItems = allClasses.map { cls ->
            StatItem(cls, stats[cls.code] ?: 0, maxVal, minRecommended)
        }.sortedByDescending { it.count }

        binding.rvStats.layoutManager = LinearLayoutManager(this)
        binding.rvStats.adapter       = StatsAdapter(statItems)
    }
}

data class StatItem(
    val cls           : RoadClasses.RoadClass,
    val count         : Int,
    val maxCount      : Int,
    val minRecommended: Int
)

class StatsAdapter(private val items: List<StatItem>) :
    RecyclerView.Adapter<StatsAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvCode     : TextView    = view.findViewById(R.id.tvCode)
        val tvName     : TextView    = view.findViewById(R.id.tvName)
        val tvCount    : TextView    = view.findViewById(R.id.tvCount)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val tvStatus   : TextView    = view.findViewById(R.id.tvStatus)
        val indicator  : View        = view.findViewById(R.id.colorIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stat, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item        = items[position]
        val cls         = item.cls
        val parsedColor = try {
            Color.parseColor(cls.colorHex)
        } catch (_: Exception) { Color.GRAY }

        holder.tvCode.text  = cls.code
        holder.tvName.text  = cls.nameId
        holder.tvCount.text = holder.itemView.context
            .getString(R.string.stats_photo_count, item.count)

        holder.indicator.setBackgroundColor(parsedColor)

        holder.progressBar.max      = item.maxCount.coerceAtLeast(1)
        holder.progressBar.progress = item.count
        holder.progressBar.progressTintList = ColorStateList.valueOf(parsedColor)

        holder.tvStatus.text = holder.itemView.context.getString(
            when {
                item.count == 0                      -> R.string.stats_status_none
                item.count < item.minRecommended / 2 -> R.string.stats_status_low
                item.count < item.minRecommended     -> R.string.stats_status_almost
                else                                 -> R.string.stats_status_ready
            },
            item.minRecommended - item.count   // hanya dipakai oleh stats_status_low
        )
    }

    override fun getItemCount() = items.size
}