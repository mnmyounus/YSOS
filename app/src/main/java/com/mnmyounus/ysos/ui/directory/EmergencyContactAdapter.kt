package com.mnmyounus.ysos.ui.directory

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mnmyounus.ysos.R
import com.mnmyounus.ysos.data.EmergencyContact
import com.mnmyounus.ysos.databinding.ItemEmergencyContactBinding

class EmergencyContactAdapter(
    private val onCallClick: (EmergencyContact) -> Unit,
    private val onEmailClick: (EmergencyContact) -> Unit
) : ListAdapter<EmergencyContact, EmergencyContactAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<EmergencyContact>() {
            override fun areItemsTheSame(a: EmergencyContact, b: EmergencyContact) = a.id == b.id
            override fun areContentsTheSame(a: EmergencyContact, b: EmergencyContact) = a == b
        }
    }

    inner class ViewHolder(private val binding: ItemEmergencyContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: EmergencyContact) {
            binding.tvContactName.text = contact.name
            binding.tvContactNumber.text = contact.phoneNumber

            // Category icon
            val iconRes = when (contact.category) {
                "police"    -> android.R.drawable.ic_menu_compass
                "ambulance" -> android.R.drawable.ic_menu_add
                "fire"      -> android.R.drawable.ic_menu_report_image
                "disaster"  -> android.R.drawable.ic_menu_info_details
                else        -> android.R.drawable.ic_menu_help
            }
            binding.ivCategoryIcon.setImageResource(iconRes)

            // Email button
            if (contact.email.isNotBlank()) {
                binding.tvContactEmail.apply {
                    text = contact.email
                    visibility = View.VISIBLE
                }
                binding.btnEmail.visibility = View.VISIBLE
                binding.btnEmail.setOnClickListener { onEmailClick(contact) }
            } else {
                binding.tvContactEmail.visibility = View.GONE
                binding.btnEmail.visibility = View.GONE
            }

            // Notes
            if (contact.notes.isNotBlank()) {
                binding.tvContactNotes.apply {
                    text = contact.notes
                    visibility = View.VISIBLE
                }
            } else {
                binding.tvContactNotes.visibility = View.GONE
            }

            binding.btnCall.setOnClickListener { onCallClick(contact) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEmergencyContactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
