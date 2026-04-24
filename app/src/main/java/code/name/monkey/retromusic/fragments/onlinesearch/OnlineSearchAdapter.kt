package code.name.monkey.retromusic.fragments.onlinesearch

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.databinding.ItemOnlineSearchResultBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

data class OnlineSearchResult(
    val id: String,          // trackSlug for SC, track id for Spotify
    val title: String,
    val artist: String,
    val album: String,       // empty for SoundCloud
    val artworkUrl: String,
    val streamKey: String,   // userSlug for SC (used with id), trackId for Spotify
    val source: SearchSource
)

enum class SearchSource { SPOTIFY, SOUNDCLOUD }

class OnlineSearchAdapter(
    private var items: List<OnlineSearchResult> = emptyList(),
    private val onPlay: (OnlineSearchResult) -> Unit,
    private val onDownload: (OnlineSearchResult) -> Unit
) : RecyclerView.Adapter<OnlineSearchAdapter.VH>() {

    inner class VH(val binding: ItemOnlineSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemOnlineSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            title.text = item.title
            artist.text = item.artist
            album.text = item.album.ifEmpty { item.source.name.lowercase().replaceFirstChar { it.uppercase() } }

            if (item.artworkUrl.isNotEmpty()) {
                Glide.with(artwork)
                    .load(item.artworkUrl)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .placeholder(code.name.monkey.retromusic.R.drawable.default_audio_art)
                    .into(artwork)
            } else {
                artwork.setImageResource(code.name.monkey.retromusic.R.drawable.default_audio_art)
            }

            playButton.setOnClickListener { onPlay(item) }
            downloadButton.setOnClickListener { onDownload(item) }
        }
    }

    override fun getItemCount() = items.size

    fun submitList(newItems: List<OnlineSearchResult>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun currentList(): List<OnlineSearchResult> = items
}
