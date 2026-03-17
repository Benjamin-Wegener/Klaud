package org.klaud.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import org.klaud.FileRepository
import org.klaud.R
import org.klaud.databinding.FragmentFileListBinding
import java.io.File

class FileListFragment : Fragment() {

    private var _binding: FragmentFileListBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var adapter: FileAdapter
    var currentDir: File = FileRepository.getSyncRoot()
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentDir = FileRepository.getSyncRoot()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFileListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FileAdapter(
            onFileClick = { syncFile ->
                if (syncFile.isDirectory) {
                    navigateToDir(syncFile.file)
                } else {
                    openFile(syncFile.file)
                }
            },
            onFileLongClick = { syncFile, anchor ->
                showContextMenu(syncFile, anchor)
            }
        )

        binding.fileList.layoutManager = LinearLayoutManager(requireContext())
        binding.fileList.adapter = adapter

        binding.fabAdd.setOnClickListener {
            AddFileBottomSheet.show(childFragmentManager)
        }
        binding.fabAdd.isEnabled = true
        binding.fabAdd.alpha = 1.0f

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentDir != FileRepository.getSyncRoot()) {
                    navigateToDir(currentDir.parentFile ?: FileRepository.getSyncRoot())
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        updateBreadcrumbs()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    fun navigateToDir(dir: File) {
        currentDir = dir
        refreshList()
        updateBreadcrumbs()
    }

    fun refreshList() {
        val files = FileRepository.listFiles(currentDir)
        adapter.updateItems(files)
    }

    private fun showContextMenu(syncFile: FileRepository.SyncFile, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.menu_file_item, popup.menu)
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename -> {
                    showRenameDialog(syncFile)
                    true
                }
                R.id.action_delete -> {
                    showDeleteConfirmation(syncFile)
                    true
                }
                R.id.action_share -> {
                    shareFile(syncFile.file)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog(syncFile: FileRepository.SyncFile) {
        val input = EditText(requireContext()).apply {
            setText(syncFile.file.name)
            setSelection(syncFile.file.name.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotEmpty() && newName != syncFile.file.name) {
                    FileRepository.renameFile(syncFile.relativePath, newName)
                    refreshList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(syncFile: FileRepository.SyncFile) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete")
            .setMessage("Are you sure you want to delete ${syncFile.file.name}?")
            .setPositiveButton("Delete") { _, _ ->
                FileRepository.deleteFile(syncFile.relativePath)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(requireContext(), "org.klaud.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = FileRepository.getMimeType(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share File"))
    }

    private fun updateBreadcrumbs() {
        binding.breadcrumbContainer.removeAllViews()
        val root = FileRepository.getSyncRoot()
        val relativePath = currentDir.absolutePath.removePrefix(root.absolutePath)
        val parts = relativePath.split(File.separator).filter { it.isNotEmpty() }

        addBreadcrumb("Klaud", root)

        var currentPath = root
        for (part in parts) {
            currentPath = File(currentPath, part)
            val dir = currentPath
            addBreadcrumb(" > ", null)
            addBreadcrumb(part, dir)
        }
    }

    private fun addBreadcrumb(text: String, dir: File?) {
        val textView = TextView(requireContext()).apply {
            this.text = text
            setPadding(8, 0, 8, 0)
            if (dir != null) {
                setTextColor(requireContext().getColor(R.color.klaud_primary))
                setOnClickListener { navigateToDir(dir) }
            }
        }
        binding.breadcrumbContainer.addView(textView)
    }

    private fun openFile(file: File) {
        val mimeType = FileRepository.getMimeType(file)
        if (mimeType.startsWith("image/")) {
            val intent = Intent(requireContext(), ImagePreviewActivity::class.java).apply {
                putExtra("relativePath", FileRepository.getRelativePath(file))
            }
            startActivity(intent)
        } else {
            val uri = FileProvider.getUriForFile(requireContext(), "org.klaud.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
