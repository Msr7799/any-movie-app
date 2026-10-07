package com.forgepulse.anymovie

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MovieDetailsActivity : AppCompatActivity() {
    private val api = ApiClient()
    private val personalLibrary by lazy { UserLibraryRepository(this) }
    private lateinit var body: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var item: LibraryItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        item = LibraryItem(
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            uri = intent.getStringExtra(EXTRA_URI).orEmpty(),
            kind = intent.getStringExtra(EXTRA_KIND) ?: "hls",
            source = intent.getStringExtra(EXTRA_SOURCE) ?: "HLS",
            pageUrl = intent.getStringExtra(EXTRA_PAGE_URL),
            catalogId = intent.getStringExtra(EXTRA_CATALOG_ID),
            tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0).takeIf { it > 0 },
            mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "movie",
            poster = intent.getStringExtra(EXTRA_POSTER),
            backdrop = intent.getStringExtra(EXTRA_BACKDROP),
        )
        personalLibrary.start()
        setContentView(buildRoot())
        val tmdbId = item.tmdbId
        if (tmdbId != null) {
            api.tmdbDetails(tmdbId, item.mediaType) { result ->
                progress.visibility = View.GONE
                result.onSuccess(::render).onFailure { renderFallback() }
            }
        } else {
            api.movieMetadata(item.title) { result ->
                progress.visibility = View.GONE
                result.onSuccess(::render).onFailure { renderFallback() }
            }
        }
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MovieDetailsActivity, R.color.cinema_bg))
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(MaterialButton(this@MovieDetailsActivity).apply {
                text = "‹"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(54), dp(48)))
            addView(TextView(this@MovieDetailsActivity).apply {
                text = getString(R.string.movie_details)
                textSize = 18f
                setTextColor(color(R.color.cinema_text))
                setTypeface(typeface, 1)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(bar)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(40))
        }
        progress = ProgressBar(this)
        body.addView(progress, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun render(metadata: MovieMetadata) {
        body.removeAllViews()
        (metadata.backdrop ?: item.backdrop)?.let { url ->
            body.addView(remoteImage(url, 230, ImageView.ScaleType.CENTER_CROP), LinearLayout.LayoutParams(-1, dp(230)))
        }
        body.addView(text("يعرض الآن", 12f, color(R.color.cinema_primary)).apply { setPadding(0, dp(16), 0, dp(2)); setTypeface(typeface, 1) })
        body.addView(text("${metadata.title}${metadata.year?.let { " ($it)" } ?: ""}", 27f, color(R.color.cinema_text)).apply { setTypeface(typeface, 1) })
        val meta = listOfNotNull(
            metadata.releaseDate,
            metadata.runtime?.let { "$it ${getString(R.string.minutes)}" },
            metadata.rating?.let { "★ %.1f / 10".format(it) },
        ).joinToString("  •  ")
        body.addView(text(meta, 14f, color(R.color.cinema_muted)).apply { setPadding(0, dp(6), 0, 0) })
        if (metadata.genres.isNotEmpty()) body.addView(text(metadata.genres.joinToString("  •  "), 13f, color(R.color.cinema_accent)).apply { setPadding(0, dp(6), 0, 0) })
        metadata.overview?.let {
            body.addView(text(it, 15f, color(R.color.cinema_text)).apply {
                setLineSpacing(0f, 1.35f)
                setPadding(0, dp(16), 0, dp(14))
            })
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (item.uri.isNotBlank()) actions.addView(MaterialButton(this).apply {
            setText(R.string.watch_now)
            setOnClickListener { returnToPlayer() }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(8) })
        if (!item.catalogId.isNullOrBlank()) actions.addView(MaterialButton(this).apply {
            text = if (personalLibrary.contains(item.catalogId!!)) getString(R.string.watchlist) else getString(R.string.add_from_tmdb)
            setOnClickListener {
                personalLibrary.upsertCatalogItem(item, watchlist = true)
                text = getString(R.string.watchlist)
            }
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        if (actions.childCount > 0) body.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(14), 0, dp(14)) })

        addPeopleLine(getString(R.string.directors), metadata.directors)
        addPeopleLine(getString(R.string.writers), metadata.writers)
        if (metadata.trailers.isNotEmpty()) addTrailers(metadata.trailers)
        if (metadata.images.isNotEmpty()) addImageStrip(getString(R.string.images), metadata.images)
        if (metadata.cast.isNotEmpty()) addCast(metadata.cast)
        body.addView(text("TMDB${metadata.wikidataId?.let { " • Wikidata $it" } ?: ""}", 10f, color(R.color.cinema_muted)).apply { setPadding(0, dp(26), 0, 0) })
    }

    private fun renderFallback() {
        body.removeAllViews()
        body.addView(text(item.title, 26f, color(R.color.cinema_text)).apply { setTypeface(typeface, 1) })
        body.addView(text(getString(R.string.metadata_unavailable), 14f, color(R.color.cinema_muted)))
        if (item.uri.isNotBlank()) body.addView(MaterialButton(this).apply { setText(R.string.watch_now); setOnClickListener { returnToPlayer() } }, LinearLayout.LayoutParams(-1, dp(56)))
    }

    private fun addPeopleLine(label: String, people: List<MoviePerson>) {
        if (people.isEmpty()) return
        body.addView(text("$label: ${people.joinToString("، ") { it.name }}", 14f, color(R.color.cinema_muted)).apply { setPadding(0, dp(5), 0, dp(5)) })
    }

    private fun addTrailers(urls: List<String>) {
        body.addView(sectionTitle("Videos"))
        urls.take(4).forEachIndexed { index, url ->
            body.addView(MaterialButton(this).apply {
                text = "Trailer ${index + 1}  ↗"
                setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            }, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(7) })
        }
    }

    private fun addImageStrip(label: String, urls: List<String>) {
        body.addView(sectionTitle(label))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urls.take(10).forEach { url -> row.addView(remoteImage(url, 120, ImageView.ScaleType.CENTER_CROP), LinearLayout.LayoutParams(dp(170), dp(120)).apply { marginEnd = dp(10) }) }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    private fun addCast(people: List<MoviePerson>) {
        body.addView(sectionTitle(getString(R.string.cast)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        people.take(16).forEach { person ->
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(person.image?.let { remoteImage(it, 82, ImageView.ScaleType.CENTER_CROP) } ?: ImageView(this@MovieDetailsActivity), LinearLayout.LayoutParams(dp(82), dp(82)))
                addView(text(person.name, 11f, color(R.color.cinema_text)).apply { gravity = Gravity.CENTER; maxLines = 2 }, LinearLayout.LayoutParams(dp(112), -2))
                person.role?.let { addView(text(it, 10f, color(R.color.cinema_muted)).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(dp(112), -2)) }
            }, LinearLayout.LayoutParams(dp(120), -2))
        }
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    private fun remoteImage(url: String, height: Int, scale: ImageView.ScaleType) = ImageView(this).apply {
        scaleType = scale
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color(R.color.cinema_surface_alt)) }
        clipToOutline = true
        SimpleImageLoader.load(this, url)
    }

    private fun returnToPlayer() {
        setResult(Activity.RESULT_OK, Intent()
            .putExtra(EXTRA_TITLE, item.title)
            .putExtra(EXTRA_URI, item.uri)
            .putExtra(EXTRA_KIND, item.kind)
            .putExtra(EXTRA_SOURCE, item.source)
            .putExtra(EXTRA_PAGE_URL, item.pageUrl)
            .putExtra(EXTRA_CATALOG_ID, item.catalogId)
            .putExtra(EXTRA_TMDB_ID, item.tmdbId ?: 0)
            .putExtra(EXTRA_MEDIA_TYPE, item.mediaType))
        finish()
    }

    override fun onDestroy() {
        api.close()
        personalLibrary.stop()
        super.onDestroy()
    }

    private fun sectionTitle(value: String) = text(value, 21f, color(R.color.cinema_text)).apply { setTypeface(typeface, 1); setPadding(0, dp(24), 0, dp(12)) }
    private fun text(value: String, size: Float, color: Int) = TextView(this).apply { text = value; textSize = size; setTextColor(color) }
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TITLE = "movie_title"
        const val EXTRA_URI = "movie_uri"
        const val EXTRA_KIND = "movie_kind"
        const val EXTRA_SOURCE = "movie_source"
        const val EXTRA_PAGE_URL = "movie_page_url"
        const val EXTRA_CATALOG_ID = "movie_catalog_id"
        const val EXTRA_TMDB_ID = "movie_tmdb_id"
        const val EXTRA_MEDIA_TYPE = "movie_media_type"
        const val EXTRA_POSTER = "movie_poster"
        const val EXTRA_BACKDROP = "movie_backdrop"

        fun intent(context: Context, item: LibraryItem) = Intent(context, MovieDetailsActivity::class.java)
            .putExtra(EXTRA_TITLE, item.title)
            .putExtra(EXTRA_URI, item.uri)
            .putExtra(EXTRA_KIND, item.kind)
            .putExtra(EXTRA_SOURCE, item.source)
            .putExtra(EXTRA_PAGE_URL, item.pageUrl)
            .putExtra(EXTRA_CATALOG_ID, item.catalogId)
            .putExtra(EXTRA_TMDB_ID, item.tmdbId ?: 0)
            .putExtra(EXTRA_MEDIA_TYPE, item.mediaType)
            .putExtra(EXTRA_POSTER, item.poster)
            .putExtra(EXTRA_BACKDROP, item.backdrop)
    }
}
