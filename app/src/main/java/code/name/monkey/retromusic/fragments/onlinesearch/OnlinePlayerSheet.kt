package code.name.monkey.retromusic.fragments.onlinesearch

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.tageditor.TagWriter
import code.name.monkey.retromusic.databinding.DialogLyricsPreviewBinding
import code.name.monkey.retromusic.databinding.DialogOnlinePlayerBinding
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.model.AudioTagInfo
import code.name.monkey.retromusic.network.OnlineSearchApiProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.EnumMap
import java.util.concurrent.TimeUnit

class OnlinePlayerSheet : BottomSheetDialogFragment() {

    private var _binding: DialogOnlinePlayerBinding? = null
    private val binding get() = _binding!!

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var wasPlayingOnOpen = false
    private var lyricsDialog: androidx.appcompat.app.AlertDialog? = null

    private var queue: List<OnlineSearchResult> = emptyList()
    private var currentIndex: Int = 0

    private val progressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying && !isUserSeeking) {
                    val pos = mp.currentPosition
                    val dur = mp.duration
                    binding.seekBar.max = dur
                    binding.seekBar.progress = pos
                    binding.currentTime.text = formatTime(pos)
                    binding.totalTime.text = formatTime(dur)
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    companion object {
        fun newInstance(queue: List<OnlineSearchResult>, startIndex: Int): OnlinePlayerSheet {
            return OnlinePlayerSheet().apply {
                this.queue = queue
                this.currentIndex = startIndex
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogOnlinePlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        wasPlayingOnOpen = MusicPlayerRemote.isPlaying
        if (wasPlayingOnOpen) MusicPlayerRemote.pauseSong()
        setupControls()
        loadAndPlay(currentIndex)
    }

    private fun setupControls() {
        binding.closeButton.setOnClickListener { dismiss() }

        binding.playPauseButton.setOnClickListener {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.pause()
                    binding.playPauseButton.setIconResource(R.drawable.ic_play_arrow)
                } else {
                    mp.start()
                    binding.playPauseButton.setIconResource(R.drawable.ic_pause)
                }
            }
        }

        binding.prevButton.setOnClickListener {
            if (currentIndex > 0) loadAndPlay(--currentIndex)
        }

        binding.nextButton.setOnClickListener {
            if (currentIndex < queue.size - 1) loadAndPlay(++currentIndex)
        }

        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.currentTime.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                isUserSeeking = false
                mediaPlayer?.seekTo(sb.progress)
            }
        })
    }

    private fun onLyricsClicked() {
        val result = queue.getOrNull(currentIndex) ?: return

        // Only offer to inject if it's a downloaded SoundCloud track (has a file path)
        // For streaming-only, just fetch and show
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add lyrics to song?")
            .setMessage("Fetch lyrics for \"${result.title}\"?")
            .setPositiveButton("Fetch") { _, _ -> fetchLyrics(result) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetchLyrics(result: OnlineSearchResult) {
        val loadingDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Fetching Lyrics")
            .setMessage("Searching...")
            .setCancelable(false)
            .create()
        loadingDialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val lrcResponse = withContext(Dispatchers.IO) {
                    // First try direct lookup with result metadata
                    var response = try {
                        OnlineSearchApiProvider.lrcLib.getLyrics(result.artist, result.title)
                    } catch (_: Exception) { null }

                    // If no lyrics, try searching Spotify for a better title match then retry
                    if (response?.plainLyrics.isNullOrEmpty() && response?.syncedLyrics.isNullOrEmpty()) {
                        try {
                            val spResults = OnlineSearchApiProvider.spotify.search(result.title)
                            val best = spResults.firstOrNull()
                            if (best != null) {
                                response = OnlineSearchApiProvider.lrcLib.getLyrics(best.artist, best.title)
                            }
                        } catch (_: Exception) {}
                    }
                    response
                }

                loadingDialog.dismiss()

                val synced = lrcResponse?.syncedLyrics
                val plain = lrcResponse?.plainLyrics
                val displayLyrics = synced ?: plain

                if (displayLyrics.isNullOrEmpty()) {
                    showLyricsPreview("Failed to Fetch Lyrics", null, result)
                } else {
                    showLyricsPreview(displayLyrics, synced ?: plain, result)
                }

            } catch (e: Exception) {
                loadingDialog.dismiss()
                showLyricsPreview("Failed to Fetch Lyrics", null, result)
            }
        }
    }

    private fun showLyricsPreview(displayText: String, lyricsToSave: String?, result: OnlineSearchResult) {
        val previewBinding = DialogLyricsPreviewBinding.inflate(layoutInflater)
        previewBinding.lyricsContent.text = displayText

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setView(previewBinding.root)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }

        if (!lyricsToSave.isNullOrEmpty()) {
            builder.setPositiveButton("Add to Song") { d, _ ->
                d.dismiss()
                injectLyrics(result, lyricsToSave)
            }
        }

        lyricsDialog = builder.create()
        lyricsDialog?.show()
    }

    private fun injectLyrics(result: OnlineSearchResult, lyrics: String) {
        // Build the expected file path (same naming as download)
        val fileName = "${result.artist} - ${result.title}.mp3"
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val musicDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_MUSIC
        )
        val file = File(musicDir, fileName)

        if (!file.exists()) {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage("Download the song first to save lyrics to it.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val fieldMap = EnumMap<FieldKey, String>(FieldKey::class.java)
                    fieldMap[FieldKey.LYRICS] = lyrics
                    TagWriter.writeTagsToFiles(
                        requireContext(),
                        AudioTagInfo(listOf(file.absolutePath), fieldMap, null)
                    )
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage("Lyrics saved to song.")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage("Failed to save lyrics.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun loadAndPlay(index: Int) {
        val result = queue.getOrNull(index) ?: return
        currentIndex = index

        // Close lyrics dialog if open — track changed
        lyricsDialog?.dismiss()
        lyricsDialog = null

        binding.title.text = result.title
        binding.artist.text = result.artist
        binding.currentTime.text = "0:00"
        binding.totalTime.text = "--:--"
        binding.seekBar.progress = 0
        binding.seekBar.max = 100

        binding.prevButton.isEnabled = index > 0
        binding.prevButton.alpha = if (index > 0) 1f else 0.3f
        binding.nextButton.isEnabled = index < queue.size - 1
        binding.nextButton.alpha = if (index < queue.size - 1) 1f else 0.3f

        if (result.artworkUrl.isNotEmpty()) {
            Glide.with(this)
                .load(result.artworkUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.default_audio_art)
                .into(binding.artwork)
        } else {
            binding.artwork.setImageResource(R.drawable.default_audio_art)
        }

        setLoading(true)
        mediaPlayer?.release()
        mediaPlayer = null

        val streamUrl = when (result.source) {
            SearchSource.SPOTIFY -> "https://spotify.4texasplayz4.workers.dev/d/${result.id}"
            SearchSource.SOUNDCLOUD -> null
        }

        if (streamUrl != null) {
            startMediaPlayer(streamUrl)
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val info = withContext(Dispatchers.IO) {
                        OnlineSearchApiProvider.soundCloud.getStream(result.streamKey, result.id)
                    }
                    startMediaPlayer(info.streamUrl)
                } catch (e: Exception) {
                    setLoading(false)
                }
            }
        }
    }

    private fun startMediaPlayer(url: String) {
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener { player ->
                    setLoading(false)
                    player.start()
                    binding.playPauseButton.setIconResource(R.drawable.ic_pause)
                    handler.post(progressRunnable)
                }
                setOnCompletionListener {
                    binding.playPauseButton.setIconResource(R.drawable.ic_play_arrow)
                    lyricsDialog?.dismiss()
                    lyricsDialog = null
                    if (currentIndex < queue.size - 1) loadAndPlay(++currentIndex)
                }
                setOnErrorListener { _, _, _ -> setLoading(false); true }
                prepareAsync()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            setLoading(false)
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingIndicator.isVisible = loading
        binding.playPauseButton.isVisible = !loading
    }

    private fun formatTime(ms: Int): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms.toLong())
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms.toLong()) % 60
        return "%d:%02d".format(minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        lyricsDialog?.dismiss()
        handler.removeCallbacks(progressRunnable)
        mediaPlayer?.release()
        mediaPlayer = null
        if (wasPlayingOnOpen) MusicPlayerRemote.resumePlaying()
        _binding = null
    }
}
