package zaujaani.roadsensecollector

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import org.json.JSONObject
import zaujaani.roadsensecollector.databinding.ActivityPhotoViewerBinding
import java.io.File

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding    : ActivityPhotoViewerBinding
    private lateinit var photos     : MutableList<File>
    private lateinit var cls        : RoadClasses.RoadClass
    private var infoVisible         = true

    companion object {
        const val EXTRA_CLASS_CODE  = "class_code"
        const val EXTRA_START_INDEX = "start_index"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val code  = intent.getStringExtra(EXTRA_CLASS_CODE) ?: return finish()
        // Fix: byCodeCtx mencakup built-in + custom classes
        cls       = RoadClasses.byCodeCtx(this, code)       ?: return finish()
        val start = intent.getIntExtra(EXTRA_START_INDEX, 0)

        val folder = CollectorStats.getClassFolder(this, code)
        photos = folder?.listFiles()
            ?.filter { it.extension.lowercase() in listOf("jpg", "jpeg", "png") }
            ?.sortedByDescending { it.lastModified() }
            ?.toMutableList()
            ?: mutableListOf()

        if (photos.isEmpty()) { finish(); return }

        binding.tvClassLabel.text = getString(R.string.class_label_format, cls.code, cls.nameId)
        binding.tvClassLabel.setBackgroundColor(cls.colorHex.toColorInt())

        val adapter = PhotoPagerAdapter(photos)
        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(start, false)

        binding.viewPager.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateInfo(position)
            }
        })

        updateInfo(start)

        binding.btnBack.setOnClickListener       { finish() }
        binding.btnDelete.setOnClickListener     { confirmDelete(binding.viewPager.currentItem) }
        binding.btnMove.setOnClickListener       { showMoveDialog(binding.viewPager.currentItem) }
        binding.btnToggleInfo.setOnClickListener {
            infoVisible = !infoVisible
            binding.tvGpsInfo.visibility = if (infoVisible) View.VISIBLE else View.GONE
            binding.btnToggleInfo.setText(
                if (infoVisible) R.string.btn_info_visible else R.string.btn_info_hidden
            )
        }

        // Tap foto → toggle bar
        binding.viewPager.setOnClickListener {
            val vis = binding.topBar.visibility == View.VISIBLE
            binding.topBar.visibility    = if (vis) View.GONE else View.VISIBLE
            binding.bottomBar.visibility = if (vis) View.GONE else View.VISIBLE
        }
    }

    private fun updateInfo(position: Int) {
        if (position >= photos.size) return
        val file = photos[position]

        binding.tvCounter.text = getString(R.string.photo_counter, position + 1, photos.size)

        val jsonFile = File(file.parent, file.nameWithoutExtension + ".json")
        if (jsonFile.exists()) {
            try {
                val json      = JSONObject(jsonFile.readText())
                val lat       = json.optDouble("latitude",   0.0)
                val lng       = json.optDouble("longitude",  0.0)
                val acc       = json.optDouble("accuracy_m", -1.0)
                val timestamp = json.optString("timestamp",  "-")
                val gpsValid  = json.optBoolean("gps_valid", false)

                binding.tvGpsInfo.text = buildString {
                    appendLine(getString(R.string.gps_timestamp, timestamp))
                    if (gpsValid) {
                        appendLine(getString(R.string.gps_coords,
                            "%.6f".format(lat), "%.6f".format(lng)))
                        append(getString(R.string.gps_accuracy, acc.toInt()))
                    } else {
                        append(getString(R.string.gps_unavailable))
                    }
                }
            } catch (_: Exception) {
                binding.tvGpsInfo.text = file.name
            }
        } else {
            binding.tvGpsInfo.text = file.name
        }
    }

    private fun confirmDelete(position: Int) {
        if (position >= photos.size) return
        val file = photos[position]

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(file.name)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                val jsonFile = File(file.parent, file.nameWithoutExtension + ".json")
                file.delete()
                if (jsonFile.exists()) jsonFile.delete()
                photos.removeAt(position)
                CollectorStats.recalculateFromDisk(this)

                if (photos.isEmpty()) {
                    Toast.makeText(this,
                        getString(R.string.toast_all_deleted), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    binding.viewPager.adapter?.notifyItemRemoved(position)
                    binding.viewPager.adapter?.notifyItemRangeChanged(position, photos.size)
                    updateInfo(binding.viewPager.currentItem)
                    Toast.makeText(this,
                        getString(R.string.toast_photo_deleted), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showMoveDialog(position: Int) {
        if (position >= photos.size) return
        val file = photos[position]
        // Fix: getAllClasses mencakup built-in + custom
        val classList = RoadClasses.getAllClasses(this).filter { it.code != cls.code }
        val names     = classList.map {
            getString(R.string.class_label_format, it.code, it.nameId)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_move_title)
            .setItems(names) { _, which ->
                val targetCls    = classList[which]
                val targetFolder = CollectorStats.getClassFolder(this, targetCls.code)
                targetFolder?.mkdirs()
                val destPhoto = File(targetFolder, file.name)
                val jsonFile  = File(file.parent, file.nameWithoutExtension + ".json")

                // Fix: copy+delete supaya aman lintas filesystem di Android 10+
                val moved = try {
                    file.copyTo(destPhoto, overwrite = true)
                    file.delete()
                    true
                } catch (_: Exception) { false }

                if (moved) {
                    if (jsonFile.exists()) {
                        try {
                            jsonFile.copyTo(File(targetFolder, jsonFile.name), overwrite = true)
                            jsonFile.delete()
                        } catch (_: Exception) {}
                    }
                    photos.removeAt(position)
                    CollectorStats.recalculateFromDisk(this)

                    if (photos.isEmpty()) {
                        finish()
                    } else {
                        binding.viewPager.adapter?.notifyItemRemoved(position)
                        binding.viewPager.adapter?.notifyItemRangeChanged(position, photos.size)
                        updateInfo(binding.viewPager.currentItem)
                    }
                    Toast.makeText(this,
                        getString(R.string.toast_moved, targetCls.code), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Gagal memindahkan foto", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}

class PhotoPagerAdapter(
    private val photos: List<File>
) : RecyclerView.Adapter<PhotoPagerAdapter.VH>() {

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val img: ImageView = view.findViewById(R.id.imgFull)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo_viewer, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        Glide.with(holder.itemView.context)
            .load(photos[position])
            .fitCenter()
            .into(holder.img)
    }

    override fun getItemCount() = photos.size
}