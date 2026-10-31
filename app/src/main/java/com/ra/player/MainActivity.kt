package com.ra.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ra.player.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Sort { NEWEST, NAME, SIZE, DURATION }

    private lateinit var binding: ActivityMainBinding
    private val adapter = VideoAdapter { play(it) }
    private var allVideos: List<VideoItem> = emptyList()
    private var shown: List<VideoItem> = emptyList()
    private var query = ""
    private var sort = Sort.NEWEST
    private val io = Executors.newSingleThreadExecutor()

    private val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadVideos() else showPermissionPrompt(true)
        }

    private val openFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) playSingle(uri, queryName(uri) ?: "Video")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.allowButton.setOnClickListener { requestPermission.launch(permission) }

        setupToolbar()

        if (hasPermission()) loadVideos() else showPermissionPrompt(false)
    }

    override fun onResume() {
        super.onResume()
        // Refresh the resume progress bars after coming back from the player.
        if (shown.isNotEmpty()) adapter.notifyDataSetChanged()
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.main)
        val searchItem = binding.toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean {
                query = q.orEmpty()
                applyFilter()
                return true
            }
        })
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.sort_newest -> { sort = Sort.NEWEST; applyFilter(); true }
                R.id.sort_name -> { sort = Sort.NAME; applyFilter(); true }
                R.id.sort_size -> { sort = Sort.SIZE; applyFilter(); true }
                R.id.sort_duration -> { sort = Sort.DURATION; applyFilter(); true }
                R.id.open_url -> { askForStreamUrl(); true }
                R.id.open_file -> { openFile.launch(arrayOf("video/*", "audio/*")); true }
                else -> false
            }
        }
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionPrompt(show: Boolean) {
        binding.permissionGroup.visibility = if (show) View.VISIBLE else View.GONE
        binding.list.visibility = if (show) View.GONE else View.VISIBLE
        if (!show) return
    }

    private fun loadVideos() {
        showPermissionPrompt(false)
        io.execute {
            val videos = try {
                VideoRepository.load(this)
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                allVideos = videos
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allVideos else allVideos.filter {
            it.title.lowercase().contains(q) || it.folder.lowercase().contains(q)
        }
        shown = when (sort) {
            Sort.NEWEST -> filtered
            Sort.NAME -> filtered.sortedBy { it.title.lowercase() }
            Sort.SIZE -> filtered.sortedByDescending { it.sizeBytes }
            Sort.DURATION -> filtered.sortedByDescending { it.durationMs }
        }
        adapter.submitList(shown)
        binding.count.text = resources.getQuantityString(R.plurals.video_count, shown.size, shown.size)
        binding.emptyText.visibility =
            if (shown.isEmpty() && hasPermission()) View.VISIBLE else View.GONE
    }

    private fun play(item: VideoItem) {
        PlaylistHolder.items = shown
        PlaylistHolder.index = shown.indexOf(item).coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun playSingle(uri: Uri, title: String) {
        PlaylistHolder.items = listOf(VideoItem(uri, title, 0, 0, "", 0, 0))
        PlaylistHolder.index = 0
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun askForStreamUrl() {
        val input = EditText(this).apply {
            hint = getString(R.string.stream_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_stream)
            .setView(holder)
            .setPositiveButton(R.string.play) { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("rtsp://")) {
                    playSingle(Uri.parse(url), url.substringAfterLast('/').ifBlank { url })
                } else {
                    Toast.makeText(this, R.string.bad_url, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }
}
