package com.mnmyounus.ysos.ui.settings

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mnmyounus.ysos.R
import com.mnmyounus.ysos.databinding.DialogEvidenceVaultBinding
import com.mnmyounus.ysos.databinding.ItemEvidenceFileBinding
import com.mnmyounus.ysos.security.EncryptionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EvidenceVaultDialog : DialogFragment() {

    private var _binding: DialogEvidenceVaultBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogEvidenceVaultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvEvidence.layoutManager = LinearLayoutManager(requireContext())
        binding.btnCloseVault.setOnClickListener { dismiss() }

        loadEvidenceFiles()
    }

    private fun loadEvidenceFiles() {
        val evidenceDir = File(requireContext().filesDir, "evidence")
        val files = evidenceDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            binding.tvVaultEmpty.visibility = View.VISIBLE
            binding.rvEvidence.visibility = View.GONE
        } else {
            binding.tvVaultEmpty.visibility = View.GONE
            binding.rvEvidence.visibility = View.VISIBLE
            binding.rvEvidence.adapter = EvidenceAdapter(files)
        }
    }

    private fun decryptAndOpenFile(file: File) {
        try {
            val cacheDir = File(requireContext().cacheDir, "decrypted_temp").apply { mkdirs() }
            val decryptedName = if (file.name.endsWith(".enc")) file.name.removeSuffix(".enc") else file.name
            val decryptedFile = File(cacheDir, decryptedName)

            if (file.name.endsWith(".enc")) {
                EncryptionManager.decryptFile(file, decryptedFile)
            } else {
                file.copyTo(decryptedFile, overwrite = true)
            }

            val mimeType = when {
                decryptedName.endsWith(".3gp") -> "audio/3gpp"
                decryptedName.endsWith(".mp4") -> "video/mp4"
                else -> "*/*"
            }

            val uri: Uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                decryptedFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No application found to open this media.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Decryption failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteEvidenceFile(file: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.vault_delete)
            .setMessage(R.string.vault_delete_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                file.delete()
                loadEvidenceFiles()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    inner class EvidenceAdapter(private val fileList: List<File>) :
        RecyclerView.Adapter<EvidenceAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemEvidenceFileBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemEvidenceFileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val f = fileList[position]
            holder.itemBinding.tvFileName.text = f.name

            val sizeKb = f.length() / 1024
            val sizeStr = if (sizeKb > 1024) "${sizeKb / 1024} MB" else "$sizeKb KB"
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
            holder.itemBinding.tvFileInfo.text = "$dateStr · $sizeStr"

            if (f.name.contains("audio")) {
                holder.itemBinding.ivFileIcon.setImageResource(android.R.drawable.ic_btn_speak_now)
            } else {
                holder.itemBinding.ivFileIcon.setImageResource(android.R.drawable.ic_menu_camera)
            }

            holder.itemBinding.btnDecryptView.setOnClickListener {
                decryptAndOpenFile(f)
            }

            holder.itemBinding.btnDeleteFile.setOnClickListener {
                deleteEvidenceFile(f)
            }
        }

        override fun getItemCount(): Int = fileList.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "EvidenceVaultDialog"
        fun newInstance() = EvidenceVaultDialog()
    }
}
