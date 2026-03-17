package org.klaud.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.klaud.FileRepository
import org.klaud.databinding.BottomSheetAddFileBinding
import java.io.File
import java.io.FileOutputStream

class AddFileBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddFileBinding? = null
    private val binding get() = _binding!!

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    copyUriToCurrentDir(data.clipData!!.getItemAt(i).uri)
                }
            } else if (data?.data != null) {
                copyUriToCurrentDir(data.data!!)
            }
            (parentFragment as? FileListFragment)?.refreshList()
            dismiss()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAddFileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPickFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            pickFilesLauncher.launch(intent)
        }

        binding.btnCreateFolder.setOnClickListener {
            showCreateFolderDialog()
        }
    }

    private fun showCreateFolderDialog() {
        val input = EditText(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val folderName = input.text.toString()
                if (folderName.isNotEmpty()) {
                    val currentDir = (parentFragment as? FileListFragment)?.currentDir ?: FileRepository.getSyncRoot()
                    val newFolder = File(currentDir, folderName)
                    newFolder.mkdirs()
                    (parentFragment as? FileListFragment)?.refreshList()
                    dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyUriToCurrentDir(uri: Uri) {
        val contentResolver = requireContext().contentResolver
        val fileName = getFileName(uri) ?: "unnamed_${System.currentTimeMillis()}"
        val currentDir = (parentFragment as? FileListFragment)?.currentDir ?: FileRepository.getSyncRoot()
        val destFile = File(currentDir, fileName)

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            (parentFragment as? FileListFragment)?.refreshList()
        } catch (e: Exception) {
            Log.e("AddFileBottomSheet", "Error copying file", e)
            Toast.makeText(requireContext(), "Failed to add file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            AddFileBottomSheet().show(fragmentManager, "AddFileBottomSheet")
        }
    }
}
