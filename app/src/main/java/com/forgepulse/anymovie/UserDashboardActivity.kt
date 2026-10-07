package com.forgepulse.anymovie

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.forgepulse.anymovie.databinding.ActivityUserDashboardBinding
import com.forgepulse.anymovie.databinding.DialogTmdbResultsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale

/**
 * User-facing dashboard. This is intentionally separate from AdminActivity:
 * normal users manage only their own Firebase-backed library while the shared
 * playable catalog remains server/admin controlled.
 */
class UserDashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUserDashboardBinding
    private val api = ApiClient()
    private val adminApi by lazy { AdminApiClient(this) }
    private val library by lazy { UserLibraryRepository(this) }
    private val store by lazy { AppStore(this) }

    private val listAdapter = DashboardMovieAdapter(::onMovieClick)
    private val continueAdapter = DashboardShelfAdapter(::onMovieClick)
    private val watchlistAdapter = DashboardShelfAdapter(::onMovieClick)
    private val recentAdapter = DashboardShelfAdapter(::onMovieClick)
    private val catalogAdapter = DashboardShelfAdapter(::onMovieClick)

    private var entries: List<UserLibraryEntry> = emptyList()
    private var catalog: List<LibraryItem> = emptyList()
    private var mode = Mode.DASHBOARD
    private var serverStatus: ApiServiceStatus? = null
    private var lastServerError: Throwable? = null

    private enum class Mode { DASHBOARD, CATALOG, LIBRARY, WATCHLIST, RECENT, RATINGS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLists()
        setupToolbar()
        setupDrawerAnimation()
        setupNavigation()
        binding.tmdbSearchCard.visibility = View.GONE
        renderAccountState()

        library.observe(::renderLibrary)
        library.start()
        checkServer()
        loadCatalog()
        verifyAdminSession()
        selectMode(Mode.DASHBOARD, closeDrawer = false)
    }

    override fun onResume() {
        super.onResume()
        renderAccountState()
        renderSidebarHistory()
    }

    private fun setupLists() {
        binding.dashboardMovies.layoutManager = LinearLayoutManager(this)
        binding.dashboardMovies.adapter = listAdapter

        fun horizontal(view: androidx.recyclerview.widget.RecyclerView, adapter: DashboardShelfAdapter) {
            view.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            view.adapter = adapter
            view.setHasFixedSize(false)
        }
        horizontal(binding.continueList, continueAdapter)
        horizontal(binding.watchlistHomeList, watchlistAdapter)
        horizontal(binding.recentHomeList, recentAdapter)
        horizontal(binding.catalogHomeList, catalogAdapter)
    }

    private fun setupToolbar() {
        binding.dashboardToolbar.setNavigationContentDescription(R.string.dashboard_menu)
        binding.dashboardToolbar.setNavigationOnClickListener {
            binding.dashboardDrawer.openDrawer(GravityCompat.START)
        }
        binding.serverRetryButton.setOnClickListener { checkServer() }
    }

    private fun setupDrawerAnimation() {
        binding.dashboardDrawer.setScrimColor(0xB8000000.toInt())
        binding.dashboardDrawer.setDrawerElevation(26f * resources.displayMetrics.density)
        binding.dashboardDrawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                binding.dashboardContent.scaleX = 1f - (0.022f * slideOffset)
                binding.dashboardContent.scaleY = 1f - (0.022f * slideOffset)
                binding.dashboardContent.alpha = 1f - (0.10f * slideOffset)
                binding.dashboardContent.translationX = (if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1) * 10f * slideOffset
            }

            override fun onDrawerOpened(drawerView: View) {
                val children = binding.sidebarItems
                for (i in 0 until children.childCount) {
                    val child = children.getChildAt(i)
                    child.alpha = 0f
                    child.translationX = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) 30f else -30f
                    child.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .setDuration(260)
                        .setStartDelay((i * 20L).coerceAtMost(260L))
                        .start()
                }
            }
        })
    }

    private fun setupNavigation() {
        binding.navDashboard.setOnClickListener { selectMode(Mode.DASHBOARD) }
        binding.navCatalog.setOnClickListener { selectMode(Mode.CATALOG) }
        binding.navLibrary.setOnClickListener { selectMode(Mode.LIBRARY) }
        binding.navWatchlist.setOnClickListener { selectMode(Mode.WATCHLIST) }
        binding.navRecent.setOnClickListener { selectMode(Mode.RECENT) }
        binding.navRatings.setOnClickListener { selectMode(Mode.RATINGS) }
        binding.navPlayer.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        binding.navAdmin.setOnClickListener { startActivity(Intent(this, AdminActivity::class.java)) }
    }

    private fun setupSearch() {
        binding.tmdbSearchButton.setOnClickListener { performTmdbSearch() }
        binding.tmdbSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performTmdbSearch()
                true
            } else false
        }
    }

    private fun performTmdbSearch() {
        val query = binding.tmdbSearchInput.text?.toString()?.trim().orEmpty()
        if (query.length < 2) {
            showSearchState(getString(R.string.enter_two_chars), loading = false)
            return
        }
        setSearchLoading(true)
        api.tmdbSearch(query) { result ->
            setSearchLoading(false)
            result.onSuccess { values ->
                if (values.isEmpty()) {
                    showSearchState(getString(R.string.tmdb_no_results), loading = false)
                } else {
                    binding.tmdbSearchState.visibility = View.GONE
                    showTmdbResults(query, values)
                }
            }.onFailure { error ->
                showSearchState(apiErrorMessage(error), loading = false)
            }
        }
    }

    private fun setSearchLoading(loading: Boolean) {
        binding.tmdbSearchButton.isEnabled = !loading
        binding.tmdbSearchButton.setText(if (loading) R.string.searching_short else R.string.search)
        if (loading) showSearchState(getString(R.string.tmdb_searching), loading = true)
    }

    private fun showSearchState(message: String, loading: Boolean) {
        binding.tmdbSearchState.visibility = View.VISIBLE
        binding.tmdbSearchProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.tmdbSearchMessage.text = message
        binding.tmdbSearchMessage.setTextColor(getColor(if (loading) R.color.cinema_muted else R.color.cinema_danger))
    }

    private fun showTmdbResults(query: String, values: List<TmdbSearchResult>) {
        val dialog = BottomSheetDialog(this, R.style.ThemeOverlay_AnyMovie_BottomSheet)
        val sheet = DialogTmdbResultsBinding.inflate(layoutInflater)
        sheet.resultsTitle.text = getString(R.string.tmdb_results_for, query)
        val adapter = TmdbResultAdapter(
            isAlreadySaved = library::containsTmdb,
            onAdd = { result ->
                // Save immediately. This does not mutate the public catalog and works
                // even if the deployed server is still on the old API version.
                library.upsertTmdbResult(result, watchlist = true)
                binding.tmdbSearchInput.setText("")
                Toast.makeText(this, R.string.added_to_library, Toast.LENGTH_SHORT).show()
                adapterRefreshLater(sheet)

                // Enrich the local/Firebase record with genres and full metadata when
                // the new details route is available. Failure is non-fatal.
                api.tmdbDetails(result.tmdbId, result.mediaType) { details ->
                    details.onSuccess { library.upsertMetadata(it, watchlist = true) }
                }
            },
        )
        sheet.resultsList.layoutManager = LinearLayoutManager(this)
        sheet.resultsList.adapter = adapter
        adapter.submitList(values)
        dialog.setContentView(sheet.root)
        dialog.setOnShowListener {
            val bottom = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottom?.setBackgroundColor(getColor(R.color.cinema_surface))
        }
        dialog.show()
    }

    /** Refreshes button state without recreating the sheet. */
    private fun adapterRefreshLater(sheet: DialogTmdbResultsBinding) {
        sheet.resultsList.postDelayed({
            (sheet.resultsList.adapter as? TmdbResultAdapter)?.notifyDataSetChanged()
        }, 120L)
    }

    private fun checkServer() {
        binding.serverProgress.visibility = View.VISIBLE
        binding.serverRetryButton.visibility = View.GONE
        binding.serverStatusBadge.setBackgroundResource(R.drawable.bg_cinema_status_warn)
        binding.serverStatusBadge.setTextColor(getColor(android.R.color.holo_orange_light))
        binding.serverStatusBadge.setText(R.string.server_checking)
        binding.serverStatusText.setText(R.string.server_connecting)
        binding.serverMetaText.text = BuildConfig.API_BASE_URL

        api.health { result ->
            binding.serverProgress.visibility = View.GONE
            result.onSuccess { status ->
                serverStatus = status
                lastServerError = null
                val oldApi = status.version.startsWith("1.") || status.version.startsWith("2.0") || status.version.startsWith("2.1")
                val healthy = status.status == "ready" || status.status == "degraded"
                binding.serverStatusBadge.setBackgroundResource(if (healthy) R.drawable.bg_cinema_status_ok else R.drawable.bg_cinema_status_warn)
                binding.serverStatusBadge.setTextColor(getColor(if (healthy) R.color.cinema_success else android.R.color.holo_orange_light))
                binding.serverStatusBadge.text = if (healthy) getString(R.string.server_online) else getString(R.string.server_degraded)
                binding.serverStatusText.text = when {
                    oldApi -> getString(R.string.server_old_api_warning)
                    status.tmdbReady -> getString(R.string.server_tmdb_ready)
                    else -> getString(R.string.server_tmdb_missing)
                }
                binding.serverMetaText.text = getString(
                    R.string.server_meta_value,
                    status.version,
                    status.region ?: "—",
                    BuildConfig.API_BASE_URL.removePrefix("https://").removePrefix("http://"),
                )
                binding.serverRetryButton.visibility = if (healthy) View.GONE else View.VISIBLE
            }.onFailure { error ->
                lastServerError = error
                binding.serverStatusBadge.setBackgroundResource(R.drawable.bg_cinema_status_warn)
                binding.serverStatusBadge.setTextColor(getColor(R.color.cinema_danger))
                binding.serverStatusBadge.setText(R.string.server_offline)
                binding.serverStatusText.text = apiErrorMessage(error)
                binding.serverMetaText.text = BuildConfig.API_BASE_URL
                binding.serverRetryButton.visibility = View.VISIBLE
            }
        }
    }

    private fun loadCatalog() {
        api.catalog { result ->
            result.onSuccess { values ->
                catalog = values
                store.saveCatalog(values)
                // If an item saved from TMDB later becomes published, migrate the
                // personal relation to the shared catalog id without losing progress.
                values.forEach { item ->
                    if (item.tmdbId != null && library.containsTmdb(item.tmdbId)) {
                        library.upsertCatalogItem(item, watchlist = false)
                    }
                }
                renderCurrentSection()
            }.onFailure {
                catalog = store.loadCatalog()
                renderCurrentSection()
            }
        }
    }

    private fun verifyAdminSession() {
        if (!adminApi.authenticated) {
            binding.navAdmin.visibility = View.VISIBLE
            return
        }
        adminApi.session { result ->
            binding.navAdmin.visibility = View.VISIBLE
            if (result.isFailure) adminApi.clearSession()
        }
    }

    private fun renderAccountState() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            binding.sidebarAccountName.setText(R.string.guest_account)
            binding.sidebarSyncStatus.setText(R.string.local_library)
            return
        }
        val user = FirebaseAuth.getInstance().currentUser
        binding.sidebarAccountName.text = user?.displayName?.takeIf { it.isNotBlank() }
            ?: user?.email?.takeIf { it.isNotBlank() }
            ?: getString(R.string.guest_account)
        binding.sidebarSyncStatus.text = when {
            user == null -> getString(R.string.firebase_connecting)
            user.isAnonymous -> getString(R.string.firebase_guest_sync)
            else -> getString(R.string.firebase_cloud_sync)
        }
    }

    private fun renderLibrary(values: List<UserLibraryEntry>) {
        entries = values
        binding.statLibrary.text = values.size.toString()
        binding.statWatchlist.text = values.count { it.watchlist }.toString()
        binding.statWatched.text = values.count { it.watched }.toString()
        binding.statRatings.text = values.count { it.rating > 0 }.toString()
        renderHomeShelves()
        renderSidebarHistory()
        renderCurrentSection()
    }

    private fun renderHomeShelves() {
        val continueWatching = entries
            .filter { it.progressMs > 0L && !it.watched }
            .sortedByDescending { it.lastWatchedAt }
            .take(12)
        val watchlist = entries.filter { it.watchlist }.sortedByDescending { it.addedAt }.take(12)
        val recent = entries.filter { it.lastWatchedAt > 0L }.sortedByDescending { it.lastWatchedAt }.take(12)
        val public = catalog.take(12).map(::catalogDisplayEntry)

        continueAdapter.submitList(continueWatching)
        watchlistAdapter.submitList(watchlist)
        recentAdapter.submitList(recent)
        catalogAdapter.submitList(public)

        binding.continueSection.isVisible = continueWatching.isNotEmpty()
        binding.watchlistHomeSection.isVisible = watchlist.isNotEmpty()
        binding.recentHomeSection.isVisible = recent.isNotEmpty()
        binding.catalogHomeSection.isVisible = public.isNotEmpty()
        binding.homeEmpty.isVisible = continueWatching.isEmpty() && watchlist.isEmpty() && recent.isEmpty() && public.isEmpty()
    }

    private fun renderSidebarHistory() {
        val container = binding.sidebarRecentList
        container.removeAllViews()
        val personal = entries.filter { it.lastWatchedAt > 0L }.sortedByDescending { it.lastWatchedAt }
        val legacy = store.loadPlaybackHistory()
        val total = (personal.map { it.title } + legacy.map { it.title }).distinct().size
        binding.sidebarHistoryCount.text = total.toString()

        val used = linkedSetOf<String>()
        personal.take(5).forEach { entry ->
            if (used.add(entry.title.lowercase(Locale.getDefault()))) {
                container.addView(sidebarHistoryRow(entry.title, getString(R.string.personal_history_item)) { onMovieClick(entry) })
            }
        }
        legacy.forEach { item ->
            if (container.childCount >= 5) return@forEach
            if (used.add(item.title.lowercase(Locale.getDefault()))) {
                container.addView(sidebarHistoryRow(item.title, item.source.ifBlank { getString(R.string.local_history_item) }) { playCatalogItem(item) })
            }
        }
        if (container.childCount == 0) {
            container.addView(sidebarHistoryRow(getString(R.string.no_recent_history), "", null))
        }
    }

    private fun sidebarHistoryRow(title: String, subtitle: String, click: (() -> Unit)?): View {
        val density = resources.displayMetrics.density
        return LinearLayoutFactory.historyRow(this, title, subtitle, click, density)
    }

    private fun selectMode(value: Mode, closeDrawer: Boolean = true) {
        mode = value
        listOf(binding.navDashboard, binding.navCatalog, binding.navLibrary, binding.navWatchlist, binding.navRecent, binding.navRatings)
            .forEach { it.isSelected = false }
        when (value) {
            Mode.DASHBOARD -> binding.navDashboard.isSelected = true
            Mode.CATALOG -> binding.navCatalog.isSelected = true
            Mode.LIBRARY -> binding.navLibrary.isSelected = true
            Mode.WATCHLIST -> binding.navWatchlist.isSelected = true
            Mode.RECENT -> binding.navRecent.isSelected = true
            Mode.RATINGS -> binding.navRatings.isSelected = true
        }
        renderCurrentSection()
        if (closeDrawer) binding.dashboardDrawer.closeDrawer(GravityCompat.START)
    }

    private fun renderCurrentSection() {
        val dashboard = mode == Mode.DASHBOARD
        binding.homeSections.isVisible = dashboard
        binding.listSection.isVisible = !dashboard
        if (dashboard) {
            renderHomeShelves()
            return
        }

        val visible = when (mode) {
            Mode.DASHBOARD -> emptyList()
            Mode.CATALOG -> catalog.map(::catalogDisplayEntry)
            Mode.LIBRARY -> entries
            Mode.WATCHLIST -> entries.filter { it.watchlist }
            Mode.RECENT -> entries.filter { it.lastWatchedAt > 0L }.sortedByDescending { it.lastWatchedAt }
            Mode.RATINGS -> entries.filter { it.rating > 0 }.sortedByDescending { it.rating }
        }
        val titleRes = when (mode) {
            Mode.DASHBOARD, Mode.LIBRARY -> R.string.my_library
            Mode.CATALOG -> R.string.public_catalog_title
            Mode.WATCHLIST -> R.string.watchlist_title
            Mode.RECENT -> R.string.recent_title
            Mode.RATINGS -> R.string.ratings_title
        }
        binding.dashboardSectionTitle.setText(titleRes)
        binding.dashboardSectionCount.text = visible.size.toString()
        binding.dashboardEmpty.isVisible = visible.isEmpty()
        binding.dashboardMovies.isVisible = visible.isNotEmpty()
        listAdapter.submit(visible)
    }

    private fun catalogDisplayEntry(item: LibraryItem): UserLibraryEntry {
        val personal = entries.firstOrNull { it.catalogId == item.catalogId || (item.tmdbId != null && it.tmdbId == item.tmdbId) }
        return personal ?: UserLibraryEntry(
            catalogId = item.catalogId.orEmpty(),
            tmdbId = item.tmdbId,
            mediaType = item.mediaType,
            title = item.title,
            poster = item.poster,
            backdrop = item.backdrop,
            overview = item.overview,
            year = item.year,
            tmdbRating = item.rating,
            genres = item.categories,
        )
    }

    private fun findCatalog(entry: UserLibraryEntry): LibraryItem? = catalog.firstOrNull {
        it.catalogId == entry.catalogId || (entry.tmdbId != null && it.tmdbId == entry.tmdbId)
    }

    private fun onMovieClick(entry: UserLibraryEntry) {
        val catalogItem = findCatalog(entry)
        val isSaved = library.contains(entry.catalogId) || (entry.tmdbId?.let(library::containsTmdb) == true)
        if (mode == Mode.CATALOG && !isSaved) {
            val actions = arrayOf(getString(R.string.add_to_library), getString(R.string.play_if_available))
            MaterialAlertDialogBuilder(this).setTitle(entry.title).setItems(actions) { _, which ->
                when (which) {
                    0 -> catalogItem?.let { library.upsertCatalogItem(it, watchlist = true) }
                    1 -> playCatalogItem(catalogItem)
                }
            }.show()
            return
        }

        val actions = arrayOf(
            getString(if (entry.watchlist) R.string.remove_watchlist else R.string.add_watchlist),
            getString(R.string.rate_movie),
            getString(R.string.play_if_available),
            getString(R.string.remove_from_library),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> library.setWatchlist(entry.catalogId, !entry.watchlist)
                    1 -> showRating(entry)
                    2 -> playCatalogItem(catalogItem)
                    3 -> library.remove(entry.catalogId)
                }
            }
            .show()
    }

    private fun showRating(entry: UserLibraryEntry) {
        val values = (1..10).map { "$it / 10" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rating_prompt)
            .setSingleChoiceItems(values, (entry.rating - 1).coerceAtLeast(-1)) { dialog, which ->
                library.setRating(entry.catalogId, which + 1)
                dialog.dismiss()
            }
            .setNeutralButton("0") { _, _ -> library.setRating(entry.catalogId, 0) }
            .show()
    }

    private fun playCatalogItem(item: LibraryItem?) {
        if (item == null || item.uri.isBlank()) {
            Toast.makeText(this, R.string.not_published_yet, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra("dashboard_play", true)
            .putExtra("dashboard_title", item.title)
            .putExtra("dashboard_uri", item.uri)
            .putExtra("dashboard_kind", item.kind)
            .putExtra("dashboard_source", item.source)
            .putExtra("dashboard_page_url", item.pageUrl)
            .putExtra("dashboard_catalog_id", item.catalogId))
    }

    private fun apiErrorMessage(error: Throwable): String {
        val apiError = error as? ApiException
        return when {
            apiError?.statusCode == 404 && apiError.endpoint?.contains("tmdb") == true -> getString(R.string.tmdb_route_not_deployed)
            apiError?.code == "TMDB_NOT_CONFIGURED" || apiError?.code == "SERVICE_NOT_CONFIGURED" -> getString(R.string.tmdb_server_not_configured)
            apiError?.statusCode == 429 -> getString(R.string.rate_limited_message)
            apiError?.statusCode == null -> getString(R.string.server_connection_failed)
            !apiError?.message.isNullOrBlank() -> apiError!!.message
            else -> getString(R.string.api_error_short)
        }
    }

    override fun onDestroy() {
        library.stop()
        api.close()
        super.onDestroy()
    }
}

/** Keeps view construction for the sidebar out of the Activity flow. */
private object LinearLayoutFactory {
    fun historyRow(context: android.content.Context, title: String, subtitle: String, click: (() -> Unit)?, density: Float): View {
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            background = context.getDrawable(R.drawable.bg_cinema_search)
            val params = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = (6 * density).toInt()
            layoutParams = params
            isClickable = click != null
            isFocusable = click != null
            setOnClickListener { click?.invoke() }
        }
        root.addView(TextView(context).apply {
            text = title
            setTextColor(context.getColor(R.color.cinema_text))
            textSize = 10.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (subtitle.isNotBlank()) root.addView(TextView(context).apply {
            text = subtitle
            setTextColor(context.getColor(R.color.cinema_muted))
            textSize = 9f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        return root
    }
}
