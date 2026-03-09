package zaujaani.roadsensecollector

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import zaujaani.roadsensecollector.databinding.ActivityLibraryBinding
import java.io.File

class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private var currentClass: RoadClasses.RoadClass? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        CollectorStats.recalculateFromDisk(this)
        setupClassList()
    }

    override fun onResume() {
        super.onResume()
        currentClass?.let { loadPhotosForClass(it) }
    }

    private fun setupClassList() {
        val adapter = ClassListAdapter(
            classes         = RoadClasses.ALL_CLASSES,
            getCount        = { cls -> CollectorStats.getCount(this, cls.code) },
            onClassSelected = { cls ->
                currentClass = cls
                loadPhotosForClass(cls)
            }
        )
        binding.rvClassList.layoutManager = LinearLayoutManager(this)
        binding.rvClassList.adapter = adapter
    }

    private fun loadPhotosForClass(cls: RoadClasses.RoadClass) {
        binding.tvCurrentClass.text = "${cls.code} — ${cls.nameId}"
        binding.tvCurrentClass.setBackgroundColor(cls.colorHex.toColorInt())

        val folder = CollectorStats.getClassFolder(this, cls.code)
        val photos = folder?.listFiles()
            ?.filter { it.extension.lowercase() in listOf("jpg", "jpeg", "png") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        binding.tvPhotoCount.text = "${photos.size} foto"

        val adapter = PhotoGridAdapter(
            photos   = photos.toMutableList(),
            onTap    = { _, position ->
                val intent = Intent(this, PhotoViewerActivity::class.java).apply {
                    putExtra(PhotoViewerActivity.EXTRA_CLASS_CODE,  cls.code)
                    putExtra(PhotoViewerActivity.EXTRA_START_INDEX, position)
                }
                startActivity(intent)
            },
            onDelete = { file, position, adp -> confirmDelete(file, position, adp) },
            onMove   = { file -> showMoveDialog(file, cls) }
        )
        binding.rvPhotos.layoutManager = GridLayoutManager(this, 3)
        binding.rvPhotos.adapter = adapter
    }

    private fun confirmDelete(file: File, position: Int, adapter: PhotoGridAdapter) {
        AlertDialog.Builder(this)
            .setTitle("Hapus foto?")
            .setMessage(file.name)
            .setPositiveButton("Hapus") { _, _ ->
                val jsonFile = File(file.parent, file.nameWithoutExtension + ".json")
                if (file.delete()) {
                    if (jsonFile.exists()) jsonFile.delete()
                    adapter.removeAt(position)
                    CollectorStats.recalculateFromDisk(this)
                    binding.tvPhotoCount.text = "${adapter.itemCount} foto"
                    Toast.makeText(this, "Foto dihapus", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showMoveDialog(file: File, currentCls: RoadClasses.RoadClass) {
        val classList = RoadClasses.ALL_CLASSES.filter { it.code != currentCls.code }
        val names     = classList.map { "${it.code} — ${it.nameId}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Pindah ke class:")
            .setItems(names) { _, which ->
                val targetCls    = classList[which]
                val targetFolder = CollectorStats.getClassFolder(this, targetCls.code)
                targetFolder?.mkdirs()
                val destFile = File(targetFolder, file.name)
                val jsonFile = File(file.parent, file.nameWithoutExtension + ".json")
                if (file.renameTo(destFile)) {
                    if (jsonFile.exists()) jsonFile.renameTo(File(targetFolder, jsonFile.name))
                    CollectorStats.recalculateFromDisk(this)
                    loadPhotosForClass(currentCls)
                    Toast.makeText(this, "✅ Dipindah ke ${targetCls.code}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Gagal pindah file", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}

class PhotoGridAdapter(
    private val photos  : MutableList<File>,
    private val onTap   : (File, Int) -> Unit,
    private val onDelete: (File, Int, PhotoGridAdapter) -> Unit,
    private val onMove  : (File) -> Unit
) : RecyclerView.Adapter<PhotoGridAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val img      : ImageView = view.findViewById(R.id.imgPhoto)
        val btnDelete: View      = view.findViewById(R.id.btnDelete)
        val btnMove  : View      = view.findViewById(R.id.btnMove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = photos[position]
        Glide.with(holder.itemView.context).load(file).centerCrop().into(holder.img)
        holder.img.setOnClickListener       { onTap(file, holder.bindingAdapterPosition) }
        holder.btnDelete.setOnClickListener { onDelete(file, holder.bindingAdapterPosition, this) }
        holder.btnMove.setOnClickListener   { onMove(file) }
    }

    override fun getItemCount() = photos.size

    fun removeAt(position: Int) {
        photos.removeAt(position)
        notifyItemRemoved(position)
    }
}

class ClassListAdapter(
    private val classes        : List<RoadClasses.RoadClass>,
    private val getCount       : (RoadClasses.RoadClass) -> Int,
    private val onClassSelected: (RoadClasses.RoadClass) -> Unit
) : RecyclerView.Adapter<ClassListAdapter.VH>() {

    private var selectedPosition = -1

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvCode   : android.widget.TextView = view.findViewById(R.id.tvCode)
        val tvName   : android.widget.TextView = view.findViewById(R.id.tvName)
        val tvCount  : android.widget.TextView = view.findViewById(R.id.tvCount)
        val indicator: View                    = view.findViewById(R.id.colorIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_class_list, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cls   = classes[position]
        val count = getCount(cls)
        holder.tvCode.text  = cls.code
        holder.tvName.text  = cls.nameId
        holder.tvCount.text = "$count foto"
        holder.indicator.setBackgroundColor(cls.colorHex.toColorInt())
        holder.itemView.alpha = if (position == selectedPosition) 1f else 0.7f
        holder.itemView.setOnClickListener {
            val prev = selectedPosition
            selectedPosition = holder.bindingAdapterPosition
            notifyItemChanged(prev)
            notifyItemChanged(selectedPosition)
            onClassSelected(cls)
        }
    }

    override fun getItemCount() = classes.size
}