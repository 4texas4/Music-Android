package code.name.monkey.retromusic.fragments.onlinesearch

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.preference.PreferenceManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.DialogDownloadProgressBinding
import code.name.monkey.retromusic.databinding.FragmentOnlineSearchBinding
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.network.OnlineSearchApiProvider
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import code.name.monkey.retromusic.extensions.showToast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL

class OnlineSearchFragment : AbsMainActivityFragment(R.layout.fragment_online_search) {

    private var _binding: FragmentOnlineSearchBinding? = null
    private val binding get() = _binding!!

    private var currentSource = SearchSource.SPOTIFY
    private lateinit var adapter: OnlineSearchAdapter

    // Result cache — survives tab switches, expires after 30s
    private var cachedResults: List<OnlineSearchResult> = emptyList()
    private var cacheTimestamp: Long = 0L
    private val cacheExpiryMs = 30_000L

    private val prefs by lazy {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    companion object {
        private const val PREF_SOURCE = "online_search_source"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentOnlineSearchBinding.bind(view)

        mainActivity.setSupportActionBar(binding.toolbar)
        mainActivity.supportActionBar?.title = "Search"

        // Restore persisted source
        currentSource = if (prefs.getString(PREF_SOURCE, "SPOTIFY") == "SOUNDCLOUD")
            SearchSource.SOUNDCLOUD else SearchSource.SPOTIFY

        setupRecyclerView()
        setupSearch()
        setupToolbarNav()

        // Restore cached results if still fresh
        val age = System.currentTimeMillis() - cacheTimestamp
        if (cachedResults.isNotEmpty() && age < cacheExpiryMs) {
            showResults(cachedResults)
        }

        // Dynamically pad recycler so content clears the mini player + nav bar
        libraryViewModel.getFabMargin().observe(viewLifecycleOwner) { margin ->
            binding.recyclerView.setPadding(
                binding.recyclerView.paddingLeft,
                binding.recyclerView.paddingTop,
                binding.recyclerView.paddingRight,
                margin
            )
        }
    }

    private fun setupToolbarNav() {
        binding.toolbar.setNavigationOnClickListener {
            binding.searchEditText.requestFocus()
            requireContext().getSystemService<InputMethodManager>()
                ?.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupRecyclerView() {
        adapter = OnlineSearchAdapter(
            onPlay = { result -> playResult(result) },
            onDownload = { result -> downloadResult(result) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@OnlineSearchFragment.adapter
        }
        showEmpty("Search for songs")
    }

    private fun setupSearch() {
        binding.searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding.searchEditText.text?.toString()?.trim() ?: "")
                true
            } else false
        }
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) return
        hideKeyboard()
        showLoading()

        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    when (currentSource) {
                        SearchSource.SPOTIFY -> {
                            OnlineSearchApiProvider.spotify.search(query).map { track ->
                                OnlineSearchResult(
                                    id = track.id,
                                    title = track.title,
                                    artist = track.artist,
                                    album = track.album,
                                    artworkUrl = track.cover,
                                    streamKey = track.id,
                                    source = SearchSource.SPOTIFY
                                )
                            }
                        }
                        SearchSource.SOUNDCLOUD -> {
                            OnlineSearchApiProvider.soundCloud.search(query).results.map { track ->
                                OnlineSearchResult(
                                    id = track.trackSlug,
                                    title = track.title,
                                    artist = track.artist,
                                    album = "",
                                    artworkUrl = track.artwork,
                                    streamKey = track.userSlug,
                                    source = SearchSource.SOUNDCLOUD
                                )
                            }
                        }
                    }
                }
                showResults(results)
            } catch (e: Exception) {
                showEmpty("Search failed. Try again.")
            }
        }
    }

    private fun playResult(result: OnlineSearchResult) {
        val currentResults = adapter.currentList()
        val index = currentResults.indexOf(result).coerceAtLeast(0)
        OnlinePlayerSheet.newInstance(currentResults, index)
            .show(childFragmentManager, "online_player")
    }

    private fun downloadResult(result: OnlineSearchResult) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Download")
            .setMessage("${result.title}\n${result.artist}")
            .setPositiveButton("Download") { _, _ -> startDownload(result) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startDownload(result: OnlineSearchResult) {
        val dialogBinding = DialogDownloadProgressBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
        dialog.show()

        dialogBinding.downloadStatus.text = "Downloading..."
        dialogBinding.progressIndicator.isIndeterminate = true

        lifecycleScope.launch {
            try {
                val fileName = "${result.artist} - ${result.title}.mp3"
                    .replace(Regex("[/\\\\:*?\"<>|]"), "_")

                withContext(Dispatchers.IO) {
                    // Step 1: Download audio to cache
                    val cacheFile = File(requireContext().cacheDir, fileName)
                    val inputStream: InputStream = when (result.source) {
                        SearchSource.SPOTIFY ->
                            OnlineSearchApiProvider.spotify.downloadTrack(result.id).body()?.byteStream()
                                ?: throw Exception("Empty response")
                        SearchSource.SOUNDCLOUD -> {
                            val streamInfo = OnlineSearchApiProvider.soundCloud.getStream(result.streamKey, result.id)
                            downloadFromUrl(streamInfo.streamUrl)
                        }
                    }
                    inputStream.use { it.copyTo(cacheFile.outputStream()) }

                    // Step 2: Write ID3 tags into the cache file
                    if (result.source == SearchSource.SOUNDCLOUD) {
                        try {
                            val audioFile = AudioFileIO.read(cacheFile)
                            val tag = audioFile.tagOrCreateAndSetDefault
                            tag.setField(FieldKey.TITLE, result.title)
                            tag.setField(FieldKey.ARTIST, result.artist)
                            tag.setField(FieldKey.ALBUM, result.title)
                            tag.setField(FieldKey.ALBUM_ARTIST, result.artist)

                            // Embed artwork via temp file (required by jaudiotagger)
                            if (result.artworkUrl.isNotEmpty()) {
                                try {
                                    val artFile = File(requireContext().cacheDir, "art_tmp.jpg")
                                    URL(result.artworkUrl).openStream().use { it.copyTo(artFile.outputStream()) }
                                    val artwork = AndroidArtwork.createArtworkFromFile(artFile)
                                    tag.deleteArtworkField()
                                    tag.setField(artwork)
                                    artFile.delete()
                                } catch (_: Exception) {}
                            }
                            audioFile.commit()
                        } catch (_: Exception) {}
                    }

                    // Step 3: Copy tagged file to Music folder
                    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    musicDir.mkdirs()
                    val destFile = File(musicDir, fileName)
                    cacheFile.copyTo(destFile, overwrite = true)
                    cacheFile.delete()

                    // Step 4: Scan into library
                    MediaScannerConnection.scanFile(
                        requireContext(),
                        arrayOf(destFile.absolutePath),
                        arrayOf("audio/mpeg"),
                        null
                    )
                }

                dialogBinding.downloadStatus.text = "Downloaded"
                dialogBinding.progressIndicator.isIndeterminate = false
                dialogBinding.progressIndicator.progress = 100
                dialogBinding.progressPercent.isVisible = true
                dialogBinding.progressPercent.text = "100%"
                dialog.dismiss()
                showDoneDialog(result.title)

            } catch (e: Exception) {
                dialog.dismiss()
                showToast("Download failed: ${e.message}")
            }
        }
    }

    private fun downloadFromUrl(url: String): InputStream {
        return URL(url).openStream()
    }

    private fun showDoneDialog(title: String) {
        val dialogBinding = DialogDownloadProgressBinding.inflate(layoutInflater)
        dialogBinding.downloadStatus.text = "Downloaded"
        dialogBinding.progressIndicator.isIndeterminate = false
        dialogBinding.progressIndicator.progress = 100
        dialogBinding.progressPercent.isVisible = true
        dialogBinding.progressPercent.text = "Done"

        MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton("Done") { d, _ -> d.dismiss() }
            .create()
            .show()
    }

    private fun showLoading() {
        binding.progressBar.isVisible = true
        binding.recyclerView.isVisible = false
        binding.emptyView.isVisible = false
    }

    private fun showResults(results: List<OnlineSearchResult>) {
        cachedResults = results
        cacheTimestamp = System.currentTimeMillis()
        binding.progressBar.isVisible = false
        if (results.isEmpty()) {
            showEmpty("No results found")
        } else {
            binding.recyclerView.isVisible = true
            binding.emptyView.isVisible = false
            adapter.submitList(results)
        }
    }

    private fun showEmpty(message: String) {
        binding.progressBar.isVisible = false
        binding.recyclerView.isVisible = false
        binding.emptyView.isVisible = true
        binding.emptyText.text = message
    }

    private fun hideKeyboard() {
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_online_search, menu)
        updateMenuChecks(menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_source_spotify -> {
                currentSource = SearchSource.SPOTIFY
                prefs.edit().putString(PREF_SOURCE, "SPOTIFY").apply()
                requireActivity().invalidateOptionsMenu()
                true
            }
            R.id.action_source_soundcloud -> {
                currentSource = SearchSource.SOUNDCLOUD
                prefs.edit().putString(PREF_SOURCE, "SOUNDCLOUD").apply()
                requireActivity().invalidateOptionsMenu()
                true
            }
            else -> false
        }
    }

    override fun onPrepareMenu(menu: Menu) {
        super.onPrepareMenu(menu)
        updateMenuChecks(menu)
    }

    private fun updateMenuChecks(menu: Menu) {
        menu.findItem(R.id.action_source_spotify)?.isChecked = currentSource == SearchSource.SPOTIFY
        menu.findItem(R.id.action_source_soundcloud)?.isChecked = currentSource == SearchSource.SOUNDCLOUD
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
