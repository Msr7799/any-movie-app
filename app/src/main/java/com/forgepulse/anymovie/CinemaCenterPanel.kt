package com.forgepulse.anymovie

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.Locale

/** Native cinematic home. Entire catalog navigation runs locally in Kotlin, not as a WebView. */
class CinemaCenterPanel(
    context: Context,
    private val onPlay: (LibraryItem) -> Unit,
    private val onDetails: (LibraryItem) -> Unit,
    private val onSearch: () -> Unit,
    private val onAdmin: () -> Unit,
    private val onRefresh: () -> Unit,
) : LinearLayout(context) {
    private val ink = Color.rgb(245, 248, 253)
    private val muted = Color.rgb(160, 177, 200)
    private val accent = Color.rgb(122, 168, 222)
    private val movieAdapter = CinemaPosterAdapter(onPlay, onDetails)
    private val recentAdapter = CinemaPosterAdapter(onPlay, onDetails)
    private val chips = ChipGroup(context)
    private val count = text("جاري تحميل المكتبة…", 12f, muted)
    private val empty = text("أفلام المكتبة ستظهر هنا عند توافر الاتصال.", 13f, muted)
    private val recentEmpty = text("الأفلام التي تشغلها تظهر هنا للعودة إليها بسرعة.", 12f, muted)
    private val nowDescription = text("عند تشغيل الفيلم، تُجلب صورته ومعلوماته وتقييمه تلقائياً.", 12f, muted)
    private val nowPoster = ImageView(context)
    private val nowLabel = text("تفاصيل الفيلم", 15f, ink)
    private val nowInfo = LinearLayout(context).apply { orientation = VERTICAL }
    private var catalog: List<LibraryItem> = emptyList()
    private var recent: List<LibraryItem> = emptyList()
    private var category = ""
    private var query = ""
    private var current: LibraryItem? = null
    private var lastCategories: List<String> = emptyList()

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        val main = MaterialCardView(context).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.rgb(15, 26, 43))
            strokeWidth = dp(1)
            strokeColor = Color.rgb(45, 68, 95)
        }
        addView(main, LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(14) })
        val inner = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(14), dp(18), dp(14), dp(18)) }
        main.addView(inner)
        inner.addView(text("CINEMA  •  مكتبتك على كيفك", 10f, accent).apply { letterSpacing = .15f })
        inner.addView(text("سينما | مركز الأفلام", 22f, ink).apply {
            setTypeface(null, Typeface.BOLD); setPadding(0, dp(5), 0, dp(5))
        })
        inner.addView(text("مشغل واحد، مكتبة واحدة، وتحكم كامل من تطبيق Android", 12f, muted))

        val nav = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val navRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun navButton(title: String, action: () -> Unit) = MaterialButton(context).apply {
            text = title; isAllCaps = false; textSize = 12f; minHeight = dp(42)
            cornerRadius = dp(14)
            setTextColor(ink)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(27, 49, 73))
            setOnClickListener { action() }
        }
        navRow.addView(navButton("⌕  بحث ذكي", onSearch))
        navRow.addView(navButton("↻  تحديث", onRefresh))
        navRow.addView(navButton("⚙  إدارة الأفلام", onAdmin))
        nav.addView(navRow)
        inner.addView(nav, LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })

        val feature = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(12), dp(10), dp(12))
            background = rounded(Color.rgb(22, 42, 64), 18)
        }
        nowPoster.apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(Color.rgb(34, 56, 81), 12); clipToOutline = true }
        feature.addView(nowPoster, LayoutParams(dp(74), dp(102)).apply { marginEnd = dp(12) })
        nowLabel.setTypeface(null, Typeface.BOLD)
        nowInfo.addView(text("صفحة التعريف التلقائية", 10f, accent))
        nowInfo.addView(nowLabel, LayoutParams(-1, -2).apply { topMargin = dp(3) })
        nowDescription.maxLines = 4
        nowInfo.addView(nowDescription, LayoutParams(-1, -2).apply { topMargin = dp(5) })
        feature.addView(nowInfo, LayoutParams(0, -2, 1f))
        feature.setOnClickListener { current?.let(onDetails) }
        inner.addView(feature, LayoutParams(-1, -2).apply { bottomMargin = dp(18) })

        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text("الأفلام", 19f, ink).apply { setTypeface(null, Typeface.BOLD) }, LayoutParams(0, -2, 1f))
        heading.addView(count)
        inner.addView(heading)
        val search = EditText(context).apply {
            hint = "ابحث في مكتبتك…"; setHintTextColor(muted); setTextColor(ink); textSize = 14f
            setSingleLine(true); background = rounded(Color.rgb(9, 18, 32), 13)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty(); updateVisible()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        inner.addView(search, LayoutParams(-1, dp(49)).apply { topMargin = dp(12); bottomMargin = dp(8) })
        chips.isSingleSelection = true
        chips.isSelectionRequired = true
        inner.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; addView(chips)
        })
        val moviesList = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = movieAdapter
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        inner.addView(moviesList, LayoutParams(-1, dp(222)).apply { topMargin = dp(12) })
        inner.addView(empty, LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        inner.addView(text("شوهد مؤخراً", 18f, ink).apply { setTypeface(null, Typeface.BOLD) }, LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val recentList = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = recentAdapter; isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        inner.addView(recentList, LayoutParams(-1, dp(222)).apply { topMargin = dp(8) })
        inner.addView(recentEmpty)
        rebuildCategories(emptyList())
    }

    fun updateLibrary(movies: List<LibraryItem>, history: List<LibraryItem>) {
        catalog = movies.distinctBy { it.catalogId ?: it.uri }
        recent = history.distinctBy { it.uri }
        val newCategories = catalog.flatMap { it.categories }.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        if (newCategories != lastCategories) rebuildCategories(newCategories)
        recentAdapter.submit(recent.take(15))
        recentEmpty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        updateVisible()
    }

    fun setPlaying(item: LibraryItem, metadata: MovieMetadata? = null) {
        current = item
        nowLabel.text = metadata?.title?.takeIf(String::isNotBlank) ?: item.title
        val details = listOfNotNull(metadata?.year?.toString(), metadata?.rating?.let { "★ %.1f".format(Locale.US, it) }, metadata?.runtime?.let { "$it دقيقة" }).joinToString("  •  ")
        nowDescription.text = when {
            metadata != null -> listOf(details, metadata.overview.orEmpty()).filter(String::isNotBlank).joinToString("\n")
            else -> "جارٍ تجهيز بطاقة الفيلم. اضغط لعرض التفاصيل والمصادر."
        }
        SimpleImageLoader.load(nowPoster, metadata?.poster ?: item.poster ?: item.backdrop)
    }

    fun setCatalogError(message: String) {
        if (catalog.isEmpty()) {
            count.text = "غير متصل"
            empty.text = "تعذر تحديث المكتبة: $message\nيمكنك تشغيل ملفاتك وسجل المشاهدة دون انقطاع."
            empty.visibility = View.VISIBLE
        }
    }

    private fun rebuildCategories(categories: List<String>) {
        lastCategories = categories
        if (category.isNotBlank() && category !in categories) category = ""
        chips.removeAllViews()
        listOf("" to "كل التصنيفات").plus(categories.map { it to it }).forEach { (key, label) ->
            val chip = Chip(context).apply {
                text = label
                isCheckable = true
                checkedIcon = null
                setTextColor(ink)
                chipBackgroundColor = ColorStateList.valueOf(Color.rgb(31, 54, 78))
                chipStrokeColor = ColorStateList.valueOf(accent)
                chipStrokeWidth = if (key == category) dp(1).toFloat() else 0f
                isChecked = key == category
                setOnClickListener { category = key; updateVisible() }
            }
            chips.addView(chip)
        }
    }

    private fun updateVisible() {
        val filtered = catalog.filter { movie ->
            (category.isBlank() || movie.categories.any { it.equals(category, ignoreCase = true) }) &&
                (query.isBlank() || movie.title.lowercase(Locale.ROOT).contains(query) || movie.categories.any { it.lowercase(Locale.ROOT).contains(query) })
        }
        movieAdapter.submit(filtered)
        count.text = "${filtered.size} فيلم"
        empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        if (filtered.isEmpty()) empty.text = if (catalog.isEmpty()) "ستظهر أفلام المكتبة هنا بعد تحميل الكتالوج." else "ما حصلنا أفلام مطابقة للبحث أو التصنيف."
    }

    private fun text(value: String, size: Float, color: Int) = TextView(context).apply { text = value; textSize = size; setTextColor(color) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(color) }
}

/** Picture-backed cards with independent Play and Details actions. */
private class CinemaPosterAdapter(
    private val onPlay: (LibraryItem) -> Unit,
    private val onDetails: (LibraryItem) -> Unit,
) : RecyclerView.Adapter<CinemaPosterAdapter.Holder>() {
    private var values = emptyList<LibraryItem>()
    fun submit(new: List<LibraryItem>) { values = new; notifyDataSetChanged() }
    override fun getItemCount() = values.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            layoutParams = RecyclerView.LayoutParams(dp(142), -1)
        }
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(11).toFloat(); setColor(Color.rgb(33, 56, 83)) }
            clipToOutline = true
            contentDescription = "بوستر الفيلم"
        }
        column.addView(poster, LinearLayout.LayoutParams(-1, dp(144)))
        val title = TextView(context).apply { textSize = 12f; setTextColor(Color.WHITE); maxLines = 2; setTypeface(null, Typeface.BOLD) }
        column.addView(title, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val play = TextView(context).apply {
            text = "▶ تشغيل"; textSize = 11f; setTextColor(Color.rgb(154, 205, 255))
            setPadding(dp(3), 0, dp(3), 0)
        }
        val details = TextView(context).apply { text = "التفاصيل ↗"; textSize = 10f; setTextColor(Color.rgb(169, 180, 198)) }
        actions.addView(play, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(details)
        column.addView(actions)
        return Holder(column, poster, title, play, details)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val movie = values[position]
        holder.title.text = movie.title
        holder.poster.contentDescription = "صورة ${movie.title}"
        SimpleImageLoader.load(holder.poster, movie.poster ?: movie.backdrop)
        holder.poster.setOnClickListener { onPlay(movie) }
        holder.play.setOnClickListener { onPlay(movie) }
        holder.title.setOnClickListener { onDetails(movie) }
        holder.details.setOnClickListener { onDetails(movie) }
    }
    class Holder(view: View, val poster: ImageView, val title: TextView, val play: View, val details: View) : RecyclerView.ViewHolder(view)
}
