package zaujaani.roadsensecollector

import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zaujaani.roadsensecollector.databinding.ActivityExportBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ExportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener   { finish() }
        binding.btnExport.setOnClickListener { startExport() }

        CollectorStats.recalculateFromDisk(this)
        showPreview()
    }

    private fun showPreview() {
        val stats        = CollectorStats.getAllStats(this)
        val total        = stats.values.sum()
        val classesWithData = stats.count { it.value > 0 }
        val trainCount   = (total * 0.8).toInt()
        val validCount   = total - trainCount

        binding.tvPreview.text = """
            📊 Ringkasan Export:
            
            Total foto       : $total
            Class dengan data: $classesWithData / ${RoadClasses.ALL_CLASSES.size}
            Train split (80%): $trainCount foto
            Valid split (20%): $validCount foto
            
            Format  : YOLO Segmentation
            Output  : Pictures/RoadSenseCollector_Export/
        """.trimIndent()
    }

    private fun startExport() {
        binding.btnExport.isEnabled          = false
        binding.progressBar.visibility       = android.view.View.VISIBLE
        binding.tvExportLog.text             = "Memulai export...\n"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { exportToYolo() }
            binding.btnExport.isEnabled    = true
            binding.progressBar.visibility = android.view.View.GONE

            if (result.success) {
                binding.tvExportLog.append("\n✅ Export selesai!\nFolder: ${result.outputPath}")
                Toast.makeText(this@ExportActivity,
                    "✅ Export berhasil!", Toast.LENGTH_LONG).show()
            } else {
                binding.tvExportLog.append("\n❌ Error: ${result.error}")
            }
        }
    }

    private suspend fun exportToYolo(): ExportResult {
        return try {
            val timestamp   = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val baseDir     = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "RoadSenseCollector_Export_$timestamp"
            )

            val trainImgDir = File(baseDir, "train/images").also { it.mkdirs() }
            val trainLblDir = File(baseDir, "train/labels").also { it.mkdirs() }
            val validImgDir = File(baseDir, "valid/images").also { it.mkdirs() }
            val validLblDir = File(baseDir, "valid/labels").also { it.mkdirs() }

            val classesWithData = mutableListOf<RoadClasses.RoadClass>()
            val summary         = StringBuilder()
            summary.appendLine("RoadSense Collector Export — $timestamp")
            summary.appendLine("=".repeat(50))

            var totalTrain = 0
            var totalValid = 0

            RoadClasses.ALL_CLASSES.forEach { cls ->
                val folder = CollectorStats.getClassFolder(this@ExportActivity, cls.code)
                val photos = folder?.listFiles()
                    ?.filter { it.extension.lowercase() in listOf("jpg", "jpeg", "png") }
                    ?: emptyList()

                if (photos.isEmpty()) {
                    summary.appendLine("${cls.code}: 0 foto (skip)")
                    return@forEach
                }

                classesWithData.add(cls)
                val shuffled     = photos.shuffled()
                val trainCount   = (shuffled.size * 0.8).toInt().coerceAtLeast(1)
                val trainPhotos  = shuffled.take(trainCount)
                val validPhotos  = shuffled.drop(trainCount)

                trainPhotos.forEach { file ->
                    file.copyTo(File(trainImgDir, "${cls.code}_${file.name}"), overwrite = true)
                    File(trainLblDir, "${cls.code}_${file.nameWithoutExtension}.txt").createNewFile()
                }
                validPhotos.forEach { file ->
                    file.copyTo(File(validImgDir, "${cls.code}_${file.name}"), overwrite = true)
                    File(validLblDir, "${cls.code}_${file.nameWithoutExtension}.txt").createNewFile()
                }

                totalTrain += trainPhotos.size
                totalValid += validPhotos.size

                summary.appendLine("${cls.code} (${cls.nameId}): ${photos.size} foto → train:${trainPhotos.size} valid:${validPhotos.size}")

                withContext(Dispatchers.Main) {
                    binding.tvExportLog.append("✅ ${cls.code}: ${photos.size} foto\n")
                }
            }

            // dataset.yaml
            val classNames   = classesWithData.map { "\"${it.code}\"" }.joinToString(", ")
            val yamlContent  = """
path: ${baseDir.absolutePath}
train: train/images
val: valid/images
nc: ${classesWithData.size}
names: [$classNames]

# Class descriptions:
${classesWithData.joinToString("\n") { "# ${it.code}: ${it.nameId}" }}
            """.trimIndent()
            File(baseDir, "dataset.yaml").writeText(yamlContent)

            // summary.txt
            summary.appendLine("\nTotal train : $totalTrain foto")
            summary.appendLine("Total valid : $totalValid foto")
            summary.appendLine("Classes     : ${classesWithData.size}")
            summary.appendLine("\nCara pakai:")
            summary.appendLine("1. Buka Label Studio")
            summary.appendLine("2. Import folder train/images")
            summary.appendLine("3. Label setiap foto dengan polygon")
            summary.appendLine("4. Export ke YOLO Segmentation format")
            summary.appendLine("5. Upload ke Kaggle → fine-tune model")
            File(baseDir, "summary.txt").writeText(summary.toString())

            ExportResult(success = true, outputPath = baseDir.absolutePath)
        } catch (e: Exception) {
            ExportResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    data class ExportResult(
        val success    : Boolean,
        val outputPath : String = "",
        val error      : String = ""
    )
}