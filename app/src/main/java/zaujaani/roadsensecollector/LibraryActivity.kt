package zaujaani.roadsensecollector

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
        binding.btnManageClasses.setOnClickListener { showClassManagerDialog() }

        CollectorStats.recalculateFromDisk(this)
        setupClassList()
    }

    override fun onResume() {
        super.onResume()
        currentClass?.let { loadPhotosForClass(it) }
    }

    private fun setupClassList() {
        // Fix: getAllClasses(this) supaya kelas custom ikut tampil di sidebar
        val allClasses = RoadClasses.getAllClasses(this)
        val adapter = ClassListAdapter(
            classes         = allClasses,
            context         = this,
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
        try {
            binding.tvCurrentClass.setBackgroundColor(cls.colorHex.toColorInt())
        } catch (_: Exception) {
            binding.tvCurrentClass.setBackgroundColor(Color.DKGRAY)
        }

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
        // Fix: getAllClasses(this) supaya kelas custom ikut sebagai tujuan pindah
        val classList = RoadClasses.getAllClasses(this).filter { it.code != currentCls.code }
        val names     = classList.map { "${it.code} — ${it.nameId}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pindah ke class:")
            .setItems(names) { _, which ->
                val targetCls    = classList[which]
                val targetFolder = CollectorStats.getClassFolder(this, targetCls.code)
                targetFolder?.mkdirs()
                val destFile = File(targetFolder, file.name)
                val jsonFile = File(file.parent, file.nameWithoutExtension + ".json")

                // Fix: copy+delete, aman lintas filesystem di Android 10+
                val moved = try {
                    file.copyTo(destFile, overwrite = true)
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
                    CollectorStats.recalculateFromDisk(this)
                    loadPhotosForClass(currentCls)
                    Toast.makeText(this, "✅ Dipindah ke ${targetCls.code}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Gagal memindahkan foto", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ── Class Manager Dialog ──────────────────────────────────────────
    private fun showClassManagerDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("⚙ Kelola Kelas")
            .setView(buildClassManagerView())
            .setPositiveButton("Tutup", null)
            .create()
        dialog.show()
        dialog.setOnDismissListener { setupClassList() }
    }

    private fun buildClassManagerView(): View {
        val root = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        root.addView(container)

        val btnAdd = Button(this).apply {
            text = "+ Tambah Kelas Baru"
            setBackgroundColor(0xFF1B5E20.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 16 }
        }
        btnAdd.setOnClickListener { showAddClassDialog { setupClassList(); refreshManagerContainer(container) } }
        container.addView(btnAdd)

        refreshManagerContainer(container)
        return root
    }

    private fun refreshManagerContainer(container: LinearLayout) {
        if (container.childCount > 1) {
            container.removeViews(1, container.childCount - 1)
        }

        val allClasses = RoadClasses.getAllClasses(this)
        val categories = RoadClasses.CATEGORIES

        for (cat in categories) {
            val inCat = allClasses.filter { it.category == cat }
            if (inCat.isEmpty()) continue

            val tvCat = TextView(this).apply {
                text = "── ${cat.label} ──"
                textSize = 11f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 8, 0, 4)
            }
            container.addView(tvCat)

            for (cls in inCat) {
                val row = buildClassRow(cls, container)
                container.addView(row)
            }
        }
    }

    private fun buildClassRow(cls: RoadClasses.RoadClass, container: LinearLayout): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 4 }
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(24, 24).also { it.marginEnd = 8 }
            try { setBackgroundColor(cls.colorHex.toColorInt()) }
            catch (_: Exception) { setBackgroundColor(Color.GRAY) }
        }

        val tvLabel = TextView(this).apply {
            text = "${cls.code} — ${cls.nameId}"
            textSize = 12f
            setTextColor(0xFFEEEEEE.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val isEnabled = RoadClasses.isEnabled(this, cls.code)
        val btnToggle = Button(this).apply {
            text = if (isEnabled) "✅ Aktif" else "⭕ Nonaktif"
            setBackgroundColor(if (isEnabled) 0xFF2E7D32.toInt() else 0xFF555555.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 72
            ).also { it.marginEnd = 4 }
        }
        btnToggle.setOnClickListener {
            RoadClasses.toggleEnabled(this, cls.code)
            val nowEnabled = RoadClasses.isEnabled(this, cls.code)
            btnToggle.text = if (nowEnabled) "✅ Aktif" else "⭕ Nonaktif"
            btnToggle.setBackgroundColor(if (nowEnabled) 0xFF2E7D32.toInt() else 0xFF555555.toInt())
            Toast.makeText(this,
                if (nowEnabled) "${cls.code} diaktifkan" else "${cls.code} dinonaktifkan",
                Toast.LENGTH_SHORT).show()
        }

        row.addView(dot)
        row.addView(tvLabel)
        row.addView(btnToggle)

        if (!cls.isBuiltIn) {
            val btnDel = Button(this).apply {
                text = "🗑"
                setBackgroundColor(0xFF8B0000.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(72, 72)
            }
            btnDel.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Hapus kelas ${cls.code}?")
                    .setMessage("Foto dalam kelas ini tidak terhapus, hanya definisi kelasnya yang dihapus.")
                    .setPositiveButton("Hapus") { _, _ ->
                        RoadClasses.deleteCustomClass(this, cls.code)
                        refreshManagerContainer(container)
                        setupClassList()
                        Toast.makeText(this, "Kelas ${cls.code} dihapus", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            row.addView(btnDel)
        }

        return row
    }

    // ── Dialog Tambah Kelas ───────────────────────────────────────────
    private fun showAddClassDialog(onAdded: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 8)
        }

        val etCode = EditText(this).apply {
            hint = "Kode unik (contoh: C00)"
            textSize = 14f
        }
        val etName = EditText(this).apply {
            hint = "Nama kelas (contoh: Retak Bintang)"
            textSize = 14f
        }

        val colors = listOf(
            "#F44336","#E91E63","#9C27B0","#3F51B5","#2196F3",
            "#00BCD4","#4CAF50","#FF9800","#795548","#607D8B"
        )
        var selectedColor = colors[0]

        val tvColorLabel = TextView(this).apply {
            text = "Warna:"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 8, 0, 4)
        }

        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val colorDots = mutableListOf<View>()
        colors.forEach { hex ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(44, 44).also { it.marginEnd = 6 }
                setBackgroundColor(hex.toColorInt())
                alpha = if (hex == selectedColor) 1f else 0.35f
            }
            dot.setOnClickListener {
                selectedColor = hex
                colorDots.forEach { d -> d.alpha = 0.35f }
                dot.alpha = 1f
            }
            colorDots.add(dot)
            colorRow.addView(dot)
        }

        layout.addView(etCode)
        layout.addView(etName)
        layout.addView(tvColorLabel)
        layout.addView(colorRow)

        AlertDialog.Builder(this)
            .setTitle("Tambah Kelas Baru")
            .setView(layout)
            .setPositiveButton("Tambah") { _, _ ->
                val code = etCode.text.toString().trim().uppercase()
                val name = etName.text.toString().trim()
                when {
                    code.isEmpty() -> Toast.makeText(this, "Kode tidak boleh kosong", Toast.LENGTH_SHORT).show()
                    name.isEmpty() -> Toast.makeText(this, "Nama tidak boleh kosong", Toast.LENGTH_SHORT).show()
                    !RoadClasses.addCustomClass(this, code, name, RoadClasses.Category.CUSTOM, selectedColor) ->
                        Toast.makeText(this, "Kode $code sudah ada!", Toast.LENGTH_SHORT).show()
                    else -> {
                        Toast.makeText(this, "✅ Kelas $code — $name ditambahkan", Toast.LENGTH_SHORT).show()
                        onAdded()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}

// ── PhotoGridAdapter ──────────────────────────────────────────────────
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

// ── ClassListAdapter ──────────────────────────────────────────────────
class ClassListAdapter(
    private val classes        : List<RoadClasses.RoadClass>,
    private val context        : android.content.Context,
    private val getCount       : (RoadClasses.RoadClass) -> Int,
    private val onClassSelected: (RoadClasses.RoadClass) -> Unit
) : RecyclerView.Adapter<ClassListAdapter.VH>() {

    private var selectedPosition = -1

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvCode   : TextView = view.findViewById(R.id.tvCode)
        val tvName   : TextView = view.findViewById(R.id.tvName)
        val tvCount  : TextView = view.findViewById(R.id.tvCount)
        val indicator: View     = view.findViewById(R.id.colorIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_class_list, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cls     = classes[position]
        val count   = getCount(cls)
        val enabled = RoadClasses.isEnabled(context, cls.code)

        holder.tvCode.text  = if (!enabled) "${cls.code} ⭕" else cls.code
        holder.tvName.text  = cls.nameId
        holder.tvCount.text = "$count foto"

        try { holder.indicator.setBackgroundColor(cls.colorHex.toColorInt()) }
        catch (_: Exception) { holder.indicator.setBackgroundColor(android.graphics.Color.GRAY) }

        holder.itemView.alpha = when {
            !enabled -> 0.35f
            position == selectedPosition -> 1f
            else -> 0.75f
        }

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