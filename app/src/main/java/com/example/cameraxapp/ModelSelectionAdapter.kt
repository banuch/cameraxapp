package com.example.cameraxapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ModelSelectionAdapter(
    private val models: List<MeterDetector.ModelInfo>,
    private val currentModelFileName: String,
    private val onModelSelected: (MeterDetector.ModelInfo) -> Unit
) : RecyclerView.Adapter<ModelSelectionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val radioButton: RadioButton = view.findViewById(R.id.radioButton)
        val modelName: TextView = view.findViewById(R.id.modelNameTextView)
        val modelDescription: TextView = view.findViewById(R.id.modelDescriptionTextView)
        val versionText: TextView = view.findViewById(R.id.versionTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = models[position]

        holder.modelName.text = model.displayName
        holder.modelDescription.text = model.description
        holder.versionText.text = "v${model.version}"
        holder.radioButton.isChecked = model.fileName == currentModelFileName

        // Handle click on the whole item
        holder.itemView.setOnClickListener {
            onModelSelected(model)
            notifyDataSetChanged()
        }

        // Handle click on the radio button
        holder.radioButton.setOnClickListener {
            onModelSelected(model)
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = models.size
}