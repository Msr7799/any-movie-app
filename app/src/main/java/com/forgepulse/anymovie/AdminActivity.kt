package com.forgepulse.anymovie

import android.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import org.json.JSONArray

class AdminActivity : AppCompatActivity() {
    private val api by lazy { AdminApiClient(this) }
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var filmsMetric: TextView
    private lateinit var viewsMetric: TextView
    private lateinit var serverMetric: TextView
    private lateinit var movieList: LinearLayout
    private lateinit var importPreview: TextView
    private lateinit var importPreviewList: LinearLayout
    private lateinit var historyList: LinearLayout
    private lateinit var rightsConfirmed: CheckBox
    private var jsonImportField: EditText? = null
    private var importRows: List<JSONObject> = emptyList()
    private val selectedImports = linkedSetOf<Int>()
    private var catalogSnapshot = JSONArray()
    private val selectedCatalogIds = linkedSetOf<String>()
    private var catalogQuery = ""
    private val exportCatalog = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            val data = JSONObject()
                .put("schema", "any-movie-catalog-export")
                .put("exportedAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                .put("movies", catalogSnapshot)
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(data.toString(2)) }
                ?: error("تعذر كتابة الملف")
        }.onSuccess { toast("تم تصدير المكتبة بصيغة JSON") }
            .onFailure { toast(it.message ?: "تعذر تصدير JSON") }
    }
    private var previewPage = 0
    private val jsonFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("تعذر قراءة الملف")
            }.onSuccess { json ->
                if (json.length > 3_000_000) {
                    toast("حجم ملف JSON كبير جداً (الحد 3 MB)")
                } else {
                    jsonImportField?.setText(json)
                    previewImport(json)
                }
            }.onFailure { toast(it.message ?: "تعذر فتح الملف") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.admin_dashboard)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ContextCompat.getColor(this@AdminActivity, R.color.matte_background)) }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(MaterialButton(this@AdminActivity).apply { text = "‹"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(52), dp(48)))
            addView(TextView(this@AdminActivity).apply { text = getString(R.string.admin_control_center); textSize = 21f; setTextColor(color(R.color.matte_on_surface)); setTypeface(typeface, 1) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(toolbar)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(28)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (api.authenticated) verifySession() else showLogin()
    }

    private fun verifySession() = api.session { result ->
        if (result.getOrNull()?.optBoolean("authenticated") == true) showDashboard() else { api.clearSession(); showLogin() }
    }

    private fun showLogin() {
        content.removeAllViews()
        content.addView(card().apply {
            addView(titleText(getString(R.string.admin_login)))
            val username = field(getString(R.string.admin_username))
            val password = field(getString(R.string.password)).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            addView(username); addView(password)
            addView(MaterialButton(this@AdminActivity).apply {
                text = getString(R.string.sign_in)
                setOnClickListener {
                    isEnabled = false
                    api.login(username.text.toString(), password.text.toString()) { result ->
                        isEnabled = true
                        result.onSuccess { showDashboard() }.onFailure { toast(it.message ?: getString(R.string.email_sign_in_failed)) }
                    }
                }
            }, match())
        }, match())
    }

    private fun showDashboard() {
        content.removeAllViews()
        status = TextView(this).apply { text = getString(R.string.admin_loading); setTextColor(color(R.color.matte_on_surface_variant)); setPadding(dp(14), dp(10), dp(14), dp(10)) }
        content.addView(status)
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun createMetric(label: String): TextView {
            val box = card().apply { setPadding(dp(10), dp(12), dp(10), dp(12)) }
            val value = TextView(this).apply {
                text = "—"; textSize = 21f; setTypeface(null, 1)
                setTextColor(color(R.color.matte_on_surface))
            }
            box.addView(value)
            box.addView(TextView(this).apply { text = label; textSize = 11f; setTextColor(color(R.color.matte_on_surface_variant)) })
            metrics.addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
            return value
        }
        filmsMetric = createMetric("الأفلام")
        viewsMetric = createMetric("شوهد مؤخراً")
        serverMetric = createMetric("حالة السيرفر")
        content.addView(metrics, match())
        content.addView(card().apply {
            addView(titleText(getString(R.string.admin_services)))
            val refresh = MaterialButton(this@AdminActivity).apply { text = getString(R.string.admin_refresh); setOnClickListener { refreshDashboard() } }
            addView(refresh, match())
        }, match())
        val importSection = importCard()
        val addSection = addHlsCard()
        val catalogSection = catalogCard()
        val historySection = historyCard()
        val quickNav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun jumpButton(label: String, destination: View) = MaterialButton(this).apply {
            text = label; textSize = 11f; isAllCaps = false
            setOnClickListener {
                (content.parent as? ScrollView)?.post {
                    (content.parent as? ScrollView)?.smoothScrollTo(0, destination.top)
                }
            }
        }
        quickNav.addView(jumpButton("JSON", importSection), LinearLayout.LayoutParams(0, -2, 1f))
        quickNav.addView(jumpButton("المكتبة", catalogSection), LinearLayout.LayoutParams(0, -2, 1f))
        quickNav.addView(jumpButton("السجل", historySection), LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(quickNav, match())
        content.addView(importSection, match())
        content.addView(addSection, match())
        content.addView(catalogSection, match())
        content.addView(historySection, match())
        val logout = MaterialButton(this).apply { text = getString(R.string.sign_out); setOnClickListener { api.logout { api.clearSession(); showLogin() } } }
        content.addView(logout, match())
        refreshDashboard()
    }

    private fun importCard() = card().apply {
        addView(titleText(getString(R.string.admin_json_import)))
        val json = field(getString(R.string.admin_json_hint)).apply { minLines = 8; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        jsonImportField = json
        json.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                importRows = emptyList(); selectedImports.clear()
                importPreviewList.removeAllViews()
                importPreview.text = "اضغط معاينة لمراجعة الأفلام"
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        addView(json)
        addView(MaterialButton(this@AdminActivity).apply {
            text = "اختيار ملف JSON من الجهاز"
            setOnClickListener { jsonFilePicker.launch("application/json") }
        }, match())
        importPreview = TextView(this@AdminActivity).apply {
            setTextColor(color(R.color.matte_on_surface_variant))
            setPadding(0, 0, 0, dp(8))
        }
        addView(importPreview)
        importPreviewList = LinearLayout(this@AdminActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(importPreviewList, match())
        val selectActions = LinearLayout(this@AdminActivity).apply { orientation = LinearLayout.HORIZONTAL }
        selectActions.addView(MaterialButton(this@AdminActivity).apply {
            text = "تحديد الكل"
            setOnClickListener { selectedImports.clear(); selectedImports.addAll(importRows.indices); renderImportPreview() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        selectActions.addView(MaterialButton(this@AdminActivity).apply {
            text = "استبعاد المحدد"
            setOnClickListener {
                if (selectedImports.isEmpty()) return@setOnClickListener
                AlertDialog.Builder(this@AdminActivity)
                    .setTitle("حذف الأفلام المحددة من المعاينة؟")
                    .setMessage("لن تُحذف من السيرفر؛ فقط لن تُنشر مع الدفعة الحالية.")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("استبعاد") { _, _ ->
                        importRows = importRows.filterIndexed { index, _ -> index !in selectedImports }
                        selectedImports.clear(); selectedImports.addAll(importRows.indices)
                        previewPage = 0
                        renderImportPreview()
                    }.show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(selectActions, match())
        rightsConfirmed = CheckBox(this@AdminActivity).apply {
            text = getString(R.string.admin_rights_confirm)
            setTextColor(color(R.color.matte_on_surface))
        }
        addView(rightsConfirmed)
        addView(MaterialButton(this@AdminActivity).apply {
            text = getString(R.string.admin_preview_json)
            setOnClickListener { previewImport(json.text.toString()) }
        }, match())
        addView(MaterialButton(this@AdminActivity).apply {
            text = getString(R.string.admin_import_publish)
            setOnClickListener {
                if (!rightsConfirmed.isChecked) {
                    toast(getString(R.string.admin_rights_required))
                    return@setOnClickListener
                }
                // Never silently publish excluded items from the raw JSON editor.
                previewImportIfNeeded(json.text.toString())
                val chosen = importRows.filterIndexed { index, _ -> index in selectedImports }
                if (chosen.isEmpty()) { toast("حدد فيلماً واحداً على الأقل للنشر"); return@setOnClickListener }
                val payload = JSONObject().put("movies", JSONArray().apply { chosen.forEach { put(it) } })
                api.importCatalog(payload, rightsConfirmed.isChecked) { result ->
                    result.onSuccess { value ->
                        toast(getString(R.string.admin_imported, value.optInt("imported")))
                        json.text.clear()
                        importRows = emptyList()
                        selectedImports.clear()
                        renderImportPreview()
                        rightsConfirmed.isChecked = false
                        refreshDashboard()
                    }.onFailure { error -> toast(error.message.orEmpty()) }
                }
            }
        }, match())
    }

    private fun addHlsCard() = card().apply {
        addView(titleText(getString(R.string.admin_add_hls)))
        addView(TextView(this@AdminActivity).apply {
            text = getString(R.string.admin_add_hls_help)
            setTextColor(color(R.color.matte_on_surface_variant))
        })
        val title = field(getString(R.string.movie_title))
        val url = field(getString(R.string.admin_hls_url)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val quality = field(getString(R.string.admin_hls_quality)).apply { setText("HLS") }
        addView(title, match()); addView(url, match()); addView(quality, match())
        addView(MaterialButton(this@AdminActivity).apply {
            text = getString(R.string.admin_add_hls)
            setOnClickListener {
                val movieTitle = title.text.toString().trim()
                val streamUrl = url.text.toString().trim()
                if (movieTitle.length < 2 || !streamUrl.startsWith("https://") || !streamUrl.contains(".m3u8", true)) {
                    toast(getString(R.string.admin_hls_invalid))
                    return@setOnClickListener
                }
                if (!rightsConfirmed.isChecked) {
                    toast(getString(R.string.admin_rights_required))
                    return@setOnClickListener
                }
                val movie = JSONObject()
                    .put("title", movieTitle)
                    .put("streams", JSONArray().put(JSONObject().put("url", streamUrl).put("quality", quality.text.toString().trim().ifBlank { "HLS" })))
                api.importCatalog(JSONObject().put("movies", JSONArray().put(movie)), rightsConfirmed.isChecked) { result ->
                    result.onSuccess { response ->
                        if (response.optInt("imported") == 0) {
                            toast(getString(R.string.admin_hls_invalid))
                        } else {
                            title.text.clear(); url.text.clear(); rightsConfirmed.isChecked = false
                            toast(getString(R.string.admin_imported, response.optInt("imported")))
                            refreshDashboard()
                        }
                    }.onFailure { error -> toast(error.message.orEmpty()) }
                }
            }
        }, match())
    }

    private fun historyCard() = card().apply {
        addView(titleText(getString(R.string.admin_history)))
        historyList = LinearLayout(this@AdminActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(historyList, match())
    }

    private fun previewImportIfNeeded(raw: String) {
        if (importRows.isEmpty()) previewImport(raw)
    }

    /** Parsed and selected on-device: only the checked records are sent for publication. */
    private fun previewImport(raw: String) {
        val parsed = runCatching { org.json.JSONTokener(raw).nextValue() }.getOrNull()
        if (parsed == null) { toast(getString(R.string.admin_invalid_json)); return }
        val movies = when (parsed) {
            is JSONArray -> (0 until parsed.length()).mapNotNull { parsed.optJSONObject(it) }
            is JSONObject -> {
                val records = parsed.optJSONArray("movies")
                when {
                    records != null -> (0 until records.length()).mapNotNull { records.optJSONObject(it) }
                    parsed.has("title") || parsed.has("name") -> listOf(parsed)
                    else -> parsed.keys().asSequence().mapNotNull { parsed.optJSONObject(it) }.toList()
                }
            }
            else -> emptyList()
        }
        importRows = movies.filter { movie ->
            (movie.optString("title").isNotBlank() || movie.optString("name").isNotBlank()) &&
                (movie.has("url") || movie.has("streams") || movie.has("qualities") || movie.has("sources"))
        }.take(200)
        selectedImports.clear()
        selectedImports.addAll(importRows.indices)
        previewPage = 0
        renderImportPreview()
    }

    private fun renderImportPreview() {
        if (!::importPreview.isInitialized || !::importPreviewList.isInitialized) return
        importPreview.text = "المعاينة: ${importRows.size} فيلم • المحدد للنشر: ${selectedImports.size}"
        importPreviewList.removeAllViews()
        if (importRows.isEmpty()) return
        val pageSize = 12
        val pageCount = (importRows.size + pageSize - 1) / pageSize
        previewPage = previewPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val offset = previewPage * pageSize
        importRows.drop(offset).take(pageSize).forEachIndexed { localIndex, movie ->
            val index = offset + localIndex
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            row.addView(CheckBox(this).apply {
                isChecked = index in selectedImports
                contentDescription = "تحديد ${movie.optString("title").ifBlank { movie.optString("name") }}"
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedImports.add(index) else selectedImports.remove(index)
                    importPreview.text = "المعاينة: ${importRows.size} فيلم • المحدد للنشر: ${selectedImports.size}"
                }
            })
            val posterUrl = listOf("thumbnailURL", "thumbnail", "poster", "posterURL", "image")
                .firstNotNullOfOrNull { key -> movie.optString(key).takeIf { it.startsWith("https://") } }
            row.addView(posterImage(posterUrl, 58, 86))
            val description = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), 0, 0, 0) }
            description.addView(TextView(this).apply {
                text = movie.optString("title").ifBlank { movie.optString("name") }
                setTextColor(color(R.color.matte_on_surface)); textSize = 13f; setTypeface(null, 1)
                maxLines = 2
            })
            val streams = movie.optJSONArray("qualities")?.length()
                ?: movie.optJSONArray("streams")?.length()
                ?: movie.optJSONArray("sources")?.length()
                ?: if (movie.has("url")) 1 else 0
            description.addView(TextView(this).apply {
                text = "$streams جودة / مصدر"
                textSize = 11f
                setTextColor(color(R.color.matte_on_surface_variant))
            })
            row.addView(description, LinearLayout.LayoutParams(0, -2, 1f))
            importPreviewList.addView(row, match())
        }
        if (pageCount > 1) {
            val pages = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            pages.addView(MaterialButton(this).apply {
                text = "السابق"
                isEnabled = previewPage > 0
                setOnClickListener { previewPage--; renderImportPreview() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            pages.addView(TextView(this).apply {
                text = "${previewPage + 1} / $pageCount"; gravity = Gravity.CENTER
                setTextColor(color(R.color.matte_on_surface))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            pages.addView(MaterialButton(this).apply {
                text = "التالي"
                isEnabled = previewPage + 1 < pageCount
                setOnClickListener { previewPage++; renderImportPreview() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            importPreviewList.addView(pages, match())
        }
    }

    private fun renderHistory(entries: JSONArray) {
        if (::viewsMetric.isInitialized) viewsMetric.text = entries.length().toString()
        historyList.removeAllViews()
        if (entries.length() == 0) {
            historyList.addView(TextView(this).apply {
                text = getString(R.string.admin_history_empty)
                setTextColor(color(R.color.matte_on_surface_variant))
            })
            return
        }
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val movie = entry.optJSONObject("movie") ?: JSONObject()
            val movieId = entry.optString("movieId")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(posterImage(movie.optString("poster").takeIf(String::isNotBlank), 58, 86))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            details.addView(TextView(this).apply {
                text = movie.optString("title").ifBlank { getString(R.string.unknown_movie) }
                setTextColor(color(R.color.matte_on_surface))
                setTypeface(typeface, 1)
            })
            details.addView(TextView(this).apply {
                val progress = entry.optLong("progress").coerceAtLeast(0L) / 60_000
                text = getString(R.string.admin_history_progress, progress)
                setTextColor(color(R.color.matte_on_surface_variant))
                textSize = 12f
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(MaterialButton(this@AdminActivity).apply {
                text = getString(R.string.edit)
                setOnClickListener { renameHistory(movieId, movie.optString("title")) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(MaterialButton(this@AdminActivity).apply {
                text = getString(R.string.delete)
                setOnClickListener { confirmDeleteHistory(movieId, movie.optString("title")) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            details.addView(actions)
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            historyList.addView(row, match())
        }
    }

    private fun posterImage(url: String?, widthDp: Int, heightDp: Int) = ImageView(this).apply {
        contentDescription = getString(R.string.movie_poster)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = ContextCompat.getDrawable(this@AdminActivity, R.drawable.bg_dropdown_popup)
        SimpleImageLoader.load(this, url)
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)).apply { marginEnd = dp(4) }
    }

    private fun renameHistory(movieId: String, currentTitle: String) {
        val title = field(getString(R.string.movie_title)).apply { setText(currentTitle) }
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_history_rename)
            .setView(title)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                api.renameHistory(movieId, title.text.toString().trim()) { result ->
                    result.onSuccess { refreshHistory() }.onFailure { error -> toast(error.message.orEmpty()) }
                }
            }
            .show()
    }

    private fun confirmDeleteHistory(movieId: String, title: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.admin_history_delete)
            .setMessage(title)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                api.deleteHistory(movieId) { result ->
                    result.onSuccess { refreshHistory() }.onFailure { error -> toast(error.message.orEmpty()) }
                }
            }
            .show()
    }

    private fun refreshHistory() {
        api.history { result ->
            result.onSuccess { renderHistory(it.optJSONArray("history") ?: JSONArray()) }
                .onFailure { error ->
                    historyList.removeAllViews()
                    historyList.addView(TextView(this).apply {
                        text = error.message.orEmpty()
                        setTextColor(color(R.color.matte_on_surface_variant))
                    })
                }
        }
    }

    private fun catalogCard() = card().apply {
        addView(titleText(getString(R.string.admin_catalog_manage)))
        addView(TextView(this@AdminActivity).apply {
            text = getString(R.string.admin_catalog_help)
            setTextColor(color(R.color.matte_on_surface_variant))
            setPadding(0, 0, 0, dp(10))
        })
        val search = field("ابحث في الأفلام أو التصنيفات…")
        search.setSingleLine(true)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                catalogQuery = s?.toString()?.trim()?.lowercase().orEmpty()
                renderCatalog(catalogSnapshot)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        addView(search, match())
        val actions = LinearLayout(this@AdminActivity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(MaterialButton(this@AdminActivity).apply {
            text = "تصدير JSON"
            setOnClickListener { exportCatalog.launch("any-movie-catalog.json") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(MaterialButton(this@AdminActivity).apply {
            text = "حذف المحدد"
            setOnClickListener { confirmDeleteSelectedMovies() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(actions, match())
        movieList = LinearLayout(this@AdminActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(movieList, match())
    }

    private fun renderCatalog(movies: JSONArray) {
        if (::filmsMetric.isInitialized) filmsMetric.text = movies.length().toString()
        catalogSnapshot = movies
        movieList.removeAllViews()
        val filtered = (0 until movies.length()).mapNotNull { index ->
            movies.optJSONObject(index)?.let { index to it }
        }.filter { (_, movie) ->
            catalogQuery.isBlank() || movie.optString("title").lowercase().contains(catalogQuery) ||
                movie.optJSONArray("categories")?.toString()?.lowercase()?.contains(catalogQuery) == true
        }
        if (filtered.isEmpty()) {
            movieList.addView(TextView(this).apply {
                text = if (movies.length() == 0) getString(R.string.admin_catalog_empty) else "لا توجد أفلام تطابق البحث"
                setTextColor(color(R.color.matte_on_surface_variant))
            })
            return
        }
        filtered.forEach { (index, movie) ->
            val movieId = movie.optString("id")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(color(R.color.matte_surface))
                    setStroke(dp(1), color(R.color.matte_outline))
                }
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            info.addView(CheckBox(this).apply {
                isChecked = movieId in selectedCatalogIds
                contentDescription = "تحديد ${movie.optString("title")}"
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedCatalogIds.add(movieId) else selectedCatalogIds.remove(movieId)
                }
            })
            row.addView(info, match())
            val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val image = listOf("poster", "thumbnailURL", "thumbnail", "image")
                .firstNotNullOfOrNull { key -> movie.optString(key).takeIf { it.startsWith("https://") } }
            body.addView(posterImage(image, 76, 106))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            details.addView(TextView(this@AdminActivity).apply {
                text = movie.optString("title"); textSize = 14f
                setTextColor(color(R.color.matte_on_surface)); setTypeface(null, 1)
                maxLines = 3
            })
            val categories = movie.optJSONArray("categories")?.let { values ->
                (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            details.addView(TextView(this@AdminActivity).apply {
                text = "${movie.optString("status", "published")} • " + categories.joinToString(" • ").ifBlank { "غير مصنف" }
                textSize = 11f; setTextColor(color(R.color.matte_on_surface_variant))
            })
            val actions = LinearLayout(this@AdminActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            actions.addView(MaterialButton(this@AdminActivity).apply {
                text = getString(R.string.edit); setOnClickListener { showMovieEditor(movie, index) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(MaterialButton(this@AdminActivity).apply {
                text = getString(R.string.delete); setOnClickListener { confirmDelete(movie) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            details.addView(actions, match())
            body.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(body, match())
            movieList.addView(row, match())
        }
    }

    private fun confirmDeleteSelectedMovies() {
        val ids = selectedCatalogIds.filter { it.isNotBlank() }.take(40)
        if (ids.isEmpty()) { toast("حدد الأفلام المراد حذفها أولاً"); return }
        AlertDialog.Builder(this)
            .setTitle("حذف ${ids.size} فيلم من المكتبة العامة؟")
            .setMessage("هذا الحذف دائم وسيؤثر على جميع المستخدمين. لا يمكن التراجع عنه.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("تأكيد الحذف") { _, _ -> deleteMovieBatch(ids, 0, 0) }
            .show()
    }

    private fun deleteMovieBatch(ids: List<String>, index: Int, successes: Int) {
        if (index >= ids.size) {
            selectedCatalogIds.clear()
            toast("تم حذف $successes من ${ids.size} فيلم")
            refreshDashboard()
            return
        }
        api.deleteMovie(ids[index]) { result ->
            deleteMovieBatch(ids, index + 1, successes + if (result.isSuccess) 1 else 0)
        }
    }

    private fun showMovieEditor(movie: JSONObject, fallbackOrder: Int) {
        val editor = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), 0) }
        val title = field(getString(R.string.movie_title)).apply { setText(movie.optString("title")) }
        val categories = field(getString(R.string.admin_categories_hint)).apply {
            val values = movie.optJSONArray("categories")?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
            setText(values.joinToString(", "))
        }
        val sources = field(getString(R.string.admin_hls_urls_hint)).apply {
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            val values = movie.optJSONArray("sources")
            setText(values?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("url")?.takeIf(String::isNotBlank) }.joinToString("\n")
            }.orEmpty())
        }
        val order = field(getString(R.string.admin_sort_order)).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(movie.optInt("sortOrder", fallbackOrder).toString()) }
        val statuses = arrayOf("metadata_only", "draft", "published", "archived")
        val currentStatus = movie.optString("status", "published").takeIf { it in statuses } ?: "published"
        val statusField = field("Status: metadata_only / draft / published / archived").apply {
            isFocusable = false
            setText(currentStatus)
            setOnClickListener {
                AlertDialog.Builder(this@AdminActivity)
                    .setTitle("Catalog status")
                    .setSingleChoiceItems(statuses, statuses.indexOf(text.toString()).coerceAtLeast(0)) { dialog, which ->
                        setText(statuses[which])
                        dialog.dismiss()
                    }.show()
            }
        }
        editor.addView(title); editor.addView(categories); editor.addView(sources); editor.addView(order); editor.addView(statusField)
        AlertDialog.Builder(this).setTitle(R.string.admin_edit_movie).setView(editor)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val categoryValues = categories.text.toString().split(',', '،').map(String::trim).filter(String::isNotEmpty).distinct().take(12)
                val sourceValues = JSONArray()
                val urls = sources.text.toString().lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().take(40).toList()
                urls.forEachIndexed { sourceIndex, url ->
                    sourceValues.put(JSONObject().put("url", url).put("quality", "HLS ${sourceIndex + 1}"))
                }
                api.updateMovie(movie.optString("id"), title.text.toString().trim(), categoryValues, order.text.toString().toIntOrNull() ?: fallbackOrder, statusField.text.toString(), sourceValues) { result ->
                    result.onSuccess { toast(getString(R.string.admin_saved)); refreshDashboard() }.onFailure { toast(it.message.orEmpty()) }
                }
            }.show()
    }

    private fun confirmDelete(movie: JSONObject) {
        AlertDialog.Builder(this).setTitle(R.string.admin_delete_movie)
            .setMessage(movie.optString("title"))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                api.deleteMovie(movie.optString("id")) { result ->
                    result.onSuccess { toast(getString(R.string.admin_deleted)); refreshDashboard() }.onFailure { toast(it.message.orEmpty()) }
                }
            }.show()
    }

    private fun refreshDashboard() {
        status.text = getString(R.string.admin_loading)
        refreshHistory()
        api.health { health ->
            health.onSuccess { value ->
                if (::serverMetric.isInitialized) serverMetric.text = if (value.optString("status") == "ready") "جاهز" else "متصل"
                val services = value.optJSONObject("services") ?: JSONObject()
                api.catalog { catalog ->
                    val movies = catalog.getOrNull()?.optJSONArray("movies") ?: JSONArray()
                    val count = movies.length()
                    status.text = getString(R.string.admin_status_format, value.optString("status"), services.optBoolean("tavily"), services.optBoolean("serper"), services.optBoolean("geminiSearch"), services.optBoolean("geminiSuggestions"), services.optBoolean("tmdb"), count)
                    renderCatalog(movies)
                }
            }.onFailure {
                if (::serverMetric.isInitialized) serverMetric.text = "متعذر"
                status.text = it.message
            }
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(color(R.color.matte_surface))
            setStroke(dp(1), color(R.color.matte_outline))
        }
    }
    private fun titleText(value: String) = TextView(this).apply { text = value; textSize = 19f; setTextColor(color(R.color.matte_on_surface)); setTypeface(typeface, 1); setPadding(0, 0, 0, dp(10)) }
    private fun field(hintValue: String) = EditText(this).apply { hint = hintValue; setTextColor(color(R.color.matte_on_surface)); setHintTextColor(color(R.color.matte_on_surface_variant)); setPadding(dp(14), dp(12), dp(14), dp(12)); background = ContextCompat.getDrawable(this@AdminActivity, R.drawable.bg_dropdown_popup) }
    private fun match() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) }
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
