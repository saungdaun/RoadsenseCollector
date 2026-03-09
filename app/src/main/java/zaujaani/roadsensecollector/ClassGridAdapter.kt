package zaujaani.roadsensecollector

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ClassGridAdapter(
    private val classes: List<RoadClasses.RoadClass>,
    private val onClassSelected: (RoadClasses.RoadClass) -> Unit
) : RecyclerView.Adapter<ClassGridAdapter.VH>() {

    private var selectedPosition = -1

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvCode    : TextView = view.findViewById(R.id.tvCode)
        val tvName    : TextView = view.findViewById(R.id.tvName)
        val tvCategory: TextView = view.findViewById(R.id.tvCategory)
        val container : View     = view.findViewById(R.id.container)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_class_grid, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cls        = classes[position]
        val baseColor  = cls.colorHex.toColorInt()
        val isSelected = position == selectedPosition

        holder.tvCode.text     = cls.code
        holder.tvName.text     = cls.nameId
        holder.tvCategory.text = cls.category.label

        holder.container.setBackgroundColor(
            if (isSelected) baseColor
            else Color.argb(64, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
        )
        holder.tvCode.setTextColor(if (isSelected) Color.WHITE else baseColor)
        holder.tvName.setTextColor(if (isSelected) Color.WHITE else Color.LTGRAY)

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

class ClassPickerDialog(
    context: Context,
    private val classes: List<RoadClasses.RoadClass>,
    private val onClassSelected: (RoadClasses.RoadClass) -> Unit
) : Dialog(context) {

    init {
        setContentView(R.layout.dialog_class_picker)
        setTitle("Pilih Class untuk Foto")

        val rvClasses = findViewById<RecyclerView>(R.id.rvClasses)
        val adapter   = ClassGridAdapter(classes) { cls ->
            onClassSelected(cls)
            dismiss()
        }
        rvClasses.layoutManager = GridLayoutManager(context, 2)
        rvClasses.adapter       = adapter

        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}