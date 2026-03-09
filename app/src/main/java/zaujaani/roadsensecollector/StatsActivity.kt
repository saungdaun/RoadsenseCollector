package zaujaani.roadsensecollector

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

        binding.tvTotalAll.text  = "Total semua foto: $total"

        val minRecommended = 50
        val classesReady   = stats.count { it.value >= minRecommended }
        binding.tvReadiness.text =
            "Class siap training (≥$minRecommended foto): $classesReady / ${RoadClasses.ALL_CLASSES.size}"

        val statItems = RoadClasses.ALL_CLASSES.map { cls ->
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
        val item = items[position]
        val cls  = item.cls

        holder.tvCode.text  = cls.code
        holder.tvName.text  = cls.nameId
        holder.tvCount.text = "${item.count} foto"
        holder.indicator.setBackgroundColor(
            android.graphics.Color.parseColor(cls.colorHex)
        )

        holder.progressBar.max      = item.maxCount
        holder.progressBar.progress = item.count
        holder.progressBar.progressTintList =
            android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(cls.colorHex)
            )

        holder.tvStatus.text = when {
            item.count == 0                          -> "⚪ Belum ada foto"
            item.count < item.minRecommended / 2     -> "🔴 Kurang (${item.minRecommended - item.count} lagi)"
            item.count < item.minRecommended         -> "🟡 Hampir cukup"
            else                                     -> "✅ Siap training"
        }
    }

    override fun getItemCount() = items.size
}