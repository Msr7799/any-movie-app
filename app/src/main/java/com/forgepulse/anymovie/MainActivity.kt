package com.forgepulse.anymovie

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.Settings
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.os.LocaleListCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.forgepulse.anymovie.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    private val demoMovie by lazy {
        LibraryItem(
            title = getString(R.string.demo_movie_title),
            uri = "https://vk.com/video_ext.php?oid=848028866&id=456260186",
            kind = "embed",
            source = "VK HD",
        )
    }
    private lateinit var binding: ActivityMainBinding
    private val api = ApiClient()
    private val store by lazy { AppStore(this) }
    private val firebase by lazy { FirebaseCoordinator(this, store) }
    private val personalLibrary by lazy { UserLibraryRepository(this) }
    private val adminApi by lazy { AdminApiClient(this) }
    private val historyVisitorId by lazy {
        getSharedPreferences("global_history", MODE_PRIVATE).let { prefs ->
            prefs.getString("visitor_id", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("visitor_id", it).apply()
            }
        }
    }
    private val audioExporter by lazy { AudioExporter(this) }
    private val resultsAdapter = SearchResultAdapter(::openResult, ::downloadResult, ::showResultDetails)
    private val libraryAdapter = LibraryAdapter(::openLibraryItem, ::showLibraryDetails)
    private val searchHistoryAdapter = SearchHistoryAdapter { restoreSavedSearch(it) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val storageExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AnyMovie-LocalStore").apply { isDaemon = true }
    }
    private val subtitlePrefs = SubtitlePreferences()
    private val picture = PictureAdjustments()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingPlayback: LibraryItem? = null
    private var currentItem: LibraryItem? = null
    private var subtitleUri: Uri? = null
    private var suggestionJob: Runnable? = null
    private var restoringSearchState = false
    private var cellularDialogShown = false
    private var mobileDataApproved = false
    private var pendingCellularAction: (() -> Unit)? = null
    private var fullscreen = false
    private var webCustomView: View? = null
    private var webCustomViewCallback: WebChromeClient.CustomViewCallback? = null
    private var currentEmbedOrigin: String? = null
    private val observedMediaRequests = mutableSetOf<String>()
    private var webAutoDetectGeneration = 0
    private lateinit var fullscreenBackCallback: OnBackPressedCallback
    private var accountState = FirebaseCoordinator.AccountState(configured = false)
    private var serverCatalog: List<LibraryItem> by lazy { store.loadCatalog() }
    private lateinit var cinemaCenter: CinemaCenterPanel
    private val movieEnrichment by lazy { MovieEnrichmentCache(this) }
    private val enrichmentAttempts = mutableSetOf<String>()
    private var selectedLibraryCategory: String? = null

    private val movieValues = listOf("any", "ar", "en", "hi", "tr")
    private val subtitleValues = listOf("any", "ar", "en", "tr")
    private val resultLimitValues = listOf(5, 10, 20, 30)
    private val searchProviderValues = listOf("tavily", "serper")

    private val openVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        persistReadPermission(uri)
        val title = documentName(uri) ?: getString(R.string.open_file)
        playLibraryItem(LibraryItem(title, uri.toString(), "video", getString(R.string.open_file)))
    }

    private val openSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        persistReadPermission(uri)
        subtitleUri = uri
        currentItem?.let { playLibraryItem(it, rotateOnStart = false) }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.notification_permission, Toast.LENGTH_LONG).show()
    }

    private val movieDetails = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        playLibraryItem(
            LibraryItem(
                title = data.getStringExtra(MovieDetailsActivity.EXTRA_TITLE).orEmpty(),
                uri = data.getStringExtra(MovieDetailsActivity.EXTRA_URI).orEmpty(),
                kind = data.getStringExtra(MovieDetailsActivity.EXTRA_KIND) ?: "hls",
                source = data.getStringExtra(MovieDetailsActivity.EXTRA_SOURCE) ?: "HLS",
                pageUrl = data.getStringExtra(MovieDetailsActivity.EXTRA_PAGE_URL),
                catalogId = data.getStringExtra(MovieDetailsActivity.EXTRA_CATALOG_ID),
                tmdbId = data.getIntExtra(MovieDetailsActivity.EXTRA_TMDB_ID, 0).takeIf { it > 0 },
                mediaType = data.getStringExtra(MovieDetailsActivity.EXTRA_MEDIA_TYPE) ?: "movie",
            ),
        )
    }

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            ) runOnUiThread(::showCellularWarning)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        restoreAppearance()
        super.onCreate(savedInstanceState)
        applySavedOrientation()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.drawerLayout.setDrawerElevation(20f * resources.displayMetrics.density)
        binding.drawerLayout.setScrimColor(Color.argb(179, 0, 0, 0))
        window.enterTransition = android.transition.Fade().apply { duration = 260 }

        setupToolbar()
        setupQuickAccess()
        setupResponsiveContent()
        setupLists()
        setupCinemaCenter()
        setupSearch()
        setupPlayerControls()
        setupFullscreenBackNavigation()
        setupWebView()
        setupFeatureCards()
        setupPictureInPicture()
        restorePersistentState()
        setupFirebase()
        personalLibrary.start()
        refreshAdminMenuVisibility()
        loadServerCatalog()
        connectPlayerService()
        val lastPlayed = store.loadPlaybackHistory().firstOrNull()
        playLibraryItem(playbackItemFromIntent(intent) ?: lastPlayed ?: demoMovie, rotateOnStart = false)
        requestNotificationPermission()
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
    }

    private fun restoreAppearance() {
        val prefs = getSharedPreferences("appearance", MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.getString("theme_mode", "dark")) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    private fun setupToolbar() = with(binding.toolbar) {
        setNavigationOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_language -> toggleLanguage()
                R.id.action_theme -> toggleTheme()
                R.id.action_cast -> openCastSettings()
                R.id.action_dashboard -> openUserDashboard()
                R.id.action_admin -> openAdminDashboard()
                R.id.action_settings -> showSettingsDialog()
            }
            true
        }
    }

    private fun openUserDashboard() {
        startActivity(Intent(this, UserDashboardActivity::class.java))
    }

    private fun refreshAdminMenuVisibility() {
        binding.toolbar.menu.findItem(R.id.action_admin)?.isVisible = true
        if (!adminApi.authenticated) return
        adminApi.session { result ->
            binding.toolbar.menu.findItem(R.id.action_admin)?.isVisible = true
            if (result.isFailure) adminApi.clearSession()
        }
    }

    private fun openAdminDashboard() {
        startActivity(Intent(this, AdminActivity::class.java))
    }

    private fun setupQuickAccess() {
        binding.quickHistoryButton.setOnClickListener {
            refreshHistoryLists()
            binding.mainScroll.smoothScrollTo(0, cinemaCenter.top)
        }
        binding.quickAccountButton.setOnClickListener {
            if (!accountState.configured || accountState.anonymous) {
                showSignInSheet()
            } else {
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }
        updateQuickAccessLabels()
    }

    private fun updateQuickAccessLabels() {
        if (!::binding.isInitialized) return
        val historyCount = store.loadSearchHistory().size + store.loadPlaybackHistory().size
        binding.quickHistoryButton.text = if (historyCount > 0) {
            getString(R.string.history_count_short, historyCount)
        } else {
            getString(R.string.history_short)
        }
        binding.quickAccountButton.text = when {
            !accountState.configured -> getString(R.string.account_short)
            accountState.anonymous -> getString(R.string.account_guest_short)
            !accountState.displayName.isNullOrBlank() -> accountState.displayName
            !accountState.email.isNullOrBlank() -> accountState.email?.substringBefore('@')
            else -> getString(R.string.account_short)
        }
    }

    private fun setupFirebase() {
        binding.accountSignInButton.setOnClickListener { showSignInSheet() }
        binding.accountSignOutButton.setOnClickListener { firebase.continueAsGuest() }
        binding.accountCard.setOnClickListener {
            if (accountState.configured && accountState.anonymous) showSignInSheet()
        }
        firebase.start(
            analyticsEnabled = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("analytics_enabled", true),
            onAccountChanged = { state -> runOnUiThread { renderAccountState(state) } },
            onCloudStateRestored = {
                runOnUiThread {
                    restorePersistentState()
                    Toast.makeText(this, R.string.cloud_state_restored, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    private fun loadServerCatalog() {
        api.catalog { result ->
            result.onSuccess { movies ->
                serverCatalog = movies
                storageExecutor.execute { store.saveCatalog(movies) }
                refreshHistoryLists()
            }.onFailure { error ->
                if (::cinemaCenter.isInitialized) cinemaCenter.setCatalogError(error.message ?: "تحقق من الاتصال")
            }
        }
    }

    private fun renderAccountState(state: FirebaseCoordinator.AccountState) {
        accountState = state
        updateQuickAccessLabels()
        if (!state.configured) {
            binding.accountAvatar.text = "F"
            binding.accountName.setText(R.string.guest_account)
            binding.accountStatus.setText(R.string.firebase_not_configured)
            binding.accountSignInButton.isEnabled = false
            binding.accountSignInButton.visibility = View.VISIBLE
            binding.accountSignOutButton.visibility = View.GONE
            return
        }

        if (state.anonymous) {
            binding.accountAvatar.text = "G"
            binding.accountName.setText(R.string.guest_account)
            binding.accountStatus.setText(
                if (state.cloudSyncActive && !state.uid.isNullOrBlank()) R.string.anonymous_sync_active
                else R.string.guest_local_only
            )
            binding.accountSignInButton.isEnabled = true
            binding.accountSignInButton.visibility = View.VISIBLE
            binding.accountSignOutButton.visibility = View.GONE
        } else {
            val fallbackName = if (state.provider == "email") "Email" else "Google"
            val display = state.displayName?.takeIf(String::isNotBlank) ?: state.email?.substringBefore('@') ?: fallbackName
            binding.accountAvatar.text = display.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
            binding.accountName.text = display
            val syncLabel = if (state.provider == "email") R.string.email_sync_active else R.string.google_sync_active
            binding.accountStatus.text = state.email?.takeIf(String::isNotBlank)?.let { "$it • ${getString(syncLabel)}" }
                ?: getString(syncLabel)
            binding.accountSignInButton.visibility = View.GONE
            binding.accountSignOutButton.visibility = View.VISIBLE
        }
    }

    private fun showSignInSheet() {
        if (!accountState.configured) {
            Toast.makeText(this, R.string.firebase_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_sign_in, null, false)
        dialog.setContentView(view)
        view.findViewById<View>(R.id.authCloseButton).setOnClickListener { dialog.dismiss() }
        val googleButton = view.findViewById<MaterialButton>(R.id.authGoogleButton)
        val emailButton = view.findViewById<MaterialButton>(R.id.authEmailButton)
        googleButton.setOnClickListener {
            googleButton.isEnabled = false
            emailButton.isEnabled = false
            firebase.signInWithGoogle { result ->
                runOnUiThread {
                    googleButton.isEnabled = true
                    emailButton.isEnabled = true
                    if (result.isSuccess) {
                        Toast.makeText(this, R.string.google_sign_in_success, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        val details = firebase.googleSignInErrorDetails(result.exceptionOrNull())
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.google_sign_in_failed)
                            .setMessage(getString(R.string.google_sign_in_help, details))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }
        emailButton.setOnClickListener {
            dialog.dismiss()
            showEmailAuthSheet()
        }
        dialog.show()
    }

    private fun showEmailAuthSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_email_auth, null, false)
        dialog.setContentView(view)
        val email = view.findViewById<TextInputEditText>(R.id.emailInput)
        val password = view.findViewById<TextInputEditText>(R.id.passwordInput)
        val signIn = view.findViewById<MaterialButton>(R.id.emailSignInButton)
        val create = view.findViewById<MaterialButton>(R.id.emailCreateAccountButton)
        val reset = view.findViewById<MaterialButton>(R.id.emailForgotPasswordButton)
        view.findViewById<View>(R.id.emailAuthCloseButton).setOnClickListener { dialog.dismiss() }

        fun credentials(): Pair<String, String>? {
            val emailValue = email.text?.toString()?.trim().orEmpty()
            val passwordValue = password.text?.toString().orEmpty()
            if (emailValue.isBlank() || passwordValue.isBlank()) {
                Toast.makeText(this, R.string.auth_fields_required, Toast.LENGTH_SHORT).show()
                return null
            }
            return emailValue to passwordValue
        }

        fun setBusy(busy: Boolean) {
            signIn.isEnabled = !busy
            create.isEnabled = !busy
            reset.isEnabled = !busy
            email.isEnabled = !busy
            password.isEnabled = !busy
        }

        val performSignIn = {
            credentials()?.let { (emailValue, passwordValue) ->
                setBusy(true)
                firebase.signInWithEmail(emailValue, passwordValue) { result ->
                    runOnUiThread {
                        setBusy(false)
                        Toast.makeText(
                            this,
                            if (result.isSuccess) R.string.email_sign_in_success else R.string.email_sign_in_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (result.isSuccess) dialog.dismiss()
                    }
                }
            }
            Unit
        }

        signIn.setOnClickListener { performSignIn() }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performSignIn()
                true
            } else false
        }
        create.setOnClickListener {
            credentials()?.let { (emailValue, passwordValue) ->
                setBusy(true)
                firebase.createAccountWithEmail(emailValue, passwordValue) { result ->
                    runOnUiThread {
                        setBusy(false)
                        Toast.makeText(
                            this,
                            if (result.isSuccess) R.string.email_account_created else R.string.email_account_create_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (result.isSuccess) dialog.dismiss()
                    }
                }
            }
        }
        reset.setOnClickListener {
            val emailValue = email.text?.toString()?.trim().orEmpty()
            if (emailValue.isBlank()) {
                Toast.makeText(this, R.string.auth_email_required, Toast.LENGTH_SHORT).show()
            } else {
                reset.isEnabled = false
                firebase.sendPasswordReset(emailValue) { result ->
                    runOnUiThread {
                        reset.isEnabled = true
                        Toast.makeText(
                            this,
                            if (result.isSuccess) R.string.password_reset_sent else R.string.password_reset_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun setupResponsiveContent() {
        binding.content.doOnLayout { view ->
            val maxWidth = resources.getDimensionPixelSize(R.dimen.content_max_width)
            if (view.width > maxWidth) {
                view.layoutParams = view.layoutParams.apply { width = maxWidth }
                (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    val margin = (resources.displayMetrics.widthPixels - maxWidth).coerceAtLeast(0) / 2
                    it.marginStart = margin
                    it.marginEnd = margin
                }
            }
        }
    }

    /** Primary navigation follows the Next.js cinema experience: player, films, history and search. */
    private fun setupCinemaCenter() {
        cinemaCenter = CinemaCenterPanel(
            context = this,
            onPlay = ::openLibraryItem,
            onDetails = ::openMovieDetails,
            onSearch = {
                binding.mainScroll.smoothScrollTo(0, binding.searchCard.top)
                binding.searchInput.requestFocus()
            },
            onAdmin = ::openAdminDashboard,
            onRefresh = ::loadServerCatalog,
        )
        // Immediately below the media player; playback stays central at the top.
        binding.content.addView(cinemaCenter, 3, matchWrap())
        cinemaCenter.updateLibrary(unfilteredLibrary(), store.loadPlaybackHistory())
    }

    private fun enrichPlayingMovie(item: LibraryItem) {
        if (!::cinemaCenter.isInitialized) return
        val cached = movieEnrichment.get(item.title)
        if (cached != null) {
            applyEnrichedMetadata(item, cached)
            return
        }
        cinemaCenter.setPlaying(item)
        if (item.title.length < 3 || MovieTitleNormalizer.isGeneric(item.title) || !item.uri.startsWith("http", ignoreCase = true)) return
        // The player never needs to wait for metadata. Do not repeat failed requests in a session.
        if (!enrichmentAttempts.add(item.title.lowercase(Locale.ROOT))) return
        val callback: (Result<MovieMetadata>) -> Unit = { response ->
            response.onSuccess { metadata ->
                movieEnrichment.put(item.title, metadata)
                if (currentItem?.uri == item.uri) applyEnrichedMetadata(item, metadata)
            }
        }
        if (item.tmdbId != null && item.tmdbId > 0) {
            api.tmdbDetails(item.tmdbId, item.mediaType, callback)
        } else {
            api.movieMetadata(item.title, callback)
        }
    }

    private fun applyEnrichedMetadata(item: LibraryItem, metadata: MovieMetadata) {
        if (currentItem?.uri != item.uri) return
        val enriched = item.copy(
            poster = metadata.poster ?: item.poster,
            backdrop = metadata.backdrop ?: item.backdrop,
            overview = metadata.overview ?: item.overview,
            rating = metadata.rating ?: item.rating,
            year = metadata.year ?: item.year,
            tmdbId = metadata.tmdbId ?: item.tmdbId,
        )
        currentItem = enriched
        cinemaCenter.setPlaying(enriched, metadata)
        storageExecutor.execute {
            store.savePlayback(enriched)
            firebase.syncLocalState()
            runOnUiThread { refreshHistoryLists() }
        }
    }

    private fun setupLists() {
        binding.resultsRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = resultsAdapter
            isNestedScrollingEnabled = false
        }
        binding.libraryRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = libraryAdapter
        }
        binding.libraryCategoryFilter.setOnItemClickListener { _, _, position, _ ->
            val categories = availableLibraryCategories()
            selectedLibraryCategory = if (position <= 0) null else categories.getOrNull(position - 1)
            refreshHistoryLists()
        }
        binding.searchHistoryRecycler.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = searchHistoryAdapter
        }
        binding.resultsHeader.setOnClickListener { setResultsCollapsed(binding.resultsRecycler.visibility == View.VISIBLE) }
        binding.resultsToggleButton.setOnClickListener { setResultsCollapsed(binding.resultsRecycler.visibility == View.VISIBLE) }
    }

    private fun setupSearch() {
        val providerLabels = searchProviderLabels()
        val movieLabels = movieLanguageLabels()
        val subtitleLabels = subtitleLanguageLabels()
        val resultLimitLabels = resultLimitLabels()
        binding.searchProvider.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, providerLabels))
        binding.movieLanguage.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, movieLabels))
        binding.subtitleLanguage.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, subtitleLabels))
        binding.resultLimit.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, resultLimitLabels))
        binding.searchProvider.setText(providerLabels.first(), false)
        binding.movieLanguage.setText(movieLabels.first(), false)
        binding.subtitleLanguage.setText(subtitleLabels.first(), false)
        binding.resultLimit.setText(resultLimitLabels[resultLimitValues.indexOf(10)], false)
        binding.filterToggleButton.setOnClickListener { toggleFilters() }
        binding.searchButton.setOnClickListener { search() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
        binding.searchProvider.setOnItemClickListener { _, _, _, _ -> saveSearchDraft() }
        binding.movieLanguage.setOnItemClickListener { _, _, _, _ -> saveSearchDraft() }
        binding.subtitleLanguage.setOnItemClickListener { _, _, _, _ -> saveSearchDraft() }
        binding.resultLimit.setOnItemClickListener { _, _, _, _ -> saveSearchDraft() }
        binding.allowClips.setOnCheckedChangeListener { _, _ -> saveSearchDraft() }
        binding.searchInput.addTextChangedListener(SimpleTextWatcher { value ->
            saveSearchDraft()
            suggestionJob?.let(mainHandler::removeCallbacks)
            if (value.trim().length < 3) return@SimpleTextWatcher
            suggestionJob = Runnable {
                api.suggestions(value.trim(), selectedMovieLanguage()) { suggestions ->
                    if (suggestions.isNotEmpty()) {
                        binding.searchInput.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, suggestions))
                        binding.searchInput.showDropDown()
                    }
                }
            }.also { mainHandler.postDelayed(it, 420) }
        })
    }

    private fun toggleFilters() {
        val panel = binding.filterPanel
        TransitionManager.beginDelayedTransition(
            binding.searchCard,
            AutoTransition().setDuration(520L),
        )
        if (panel.visibility == View.VISIBLE) {
            binding.filterToggleButton.setText(R.string.show_filters)
            binding.discoverTitle.visibility = View.GONE
            binding.discoverSubtitle.visibility = View.GONE
            panel.visibility = View.GONE
        } else {
            binding.filterToggleButton.setText(R.string.hide_filters)
            binding.discoverTitle.visibility = View.VISIBLE
            binding.discoverSubtitle.visibility = View.VISIBLE
            panel.visibility = View.VISIBLE
        }
    }

    private fun search() {
        val query = binding.searchInput.text.toString().trim()
        if (query.length < 2) return
        if (!canUseNetwork(::search)) return
        val draft = currentSearchDraft().copy(query = query)
        store.saveDraft(draft)
        binding.searchProgress.visibility = View.VISIBLE
        binding.searchStatus.visibility = View.VISIBLE
        binding.searchStatus.setText(R.string.loading)
        binding.searchButton.isEnabled = false
        api.search(query, draft.movieLanguage, draft.subtitleLanguage, draft.allowShortClips, draft.resultLimit, draft.searchProvider) { result ->
            binding.searchProgress.visibility = View.GONE
            binding.searchButton.isEnabled = true
            result.onSuccess { response ->
                val playable = response.copy(results = response.results.filter { it.playable && (!it.playUrl.isNullOrBlank() || !it.hlsUrl.isNullOrBlank()) })
                showSearchResponse(playable, expand = true)
                // Search history can contain many large result objects. Persist and
                // cloud-sync it away from the UI thread so tapping Search never
                // stalls the phone while JSON is serialized.
                storageExecutor.execute {
                    store.saveSearch(draft, playable)
                    firebase.syncLocalState()
                    runOnUiThread { refreshHistoryLists() }
                }
                firebase.logSearch(draft.query, playable.results.size, draft.movieLanguage, draft.subtitleLanguage)
            }.onFailure { error ->
                firebase.logSearchFailure(error)
                binding.searchStatus.text = searchFailureMessage(error)
            }
        }
    }

    private fun searchFailureMessage(error: Throwable): String {
        val apiError = error as? ApiException
        val message = when {
            apiError?.statusCode == 429 -> getString(R.string.rate_limited_message)
            apiError?.statusCode == 404 -> getString(R.string.search_route_not_deployed)
            apiError?.code == "SERVICE_NOT_CONFIGURED" || apiError?.code?.startsWith("MISSING_ENV") == true ->
                getString(R.string.search_service_not_configured)
            apiError?.statusCode == null -> getString(R.string.server_connection_failed)
            !apiError?.message.isNullOrBlank() -> apiError!!.message
            else -> getString(R.string.search_failed)
        }
        return apiError?.requestId?.takeIf { it.isNotBlank() }?.let {
            "$message\n${getString(R.string.request_id_value, it)}"
        } ?: message
    }

    private fun selectedMovieLanguage(): String {
        val labels = movieLanguageLabels()
        return movieValues.getOrElse(labels.indexOf(binding.movieLanguage.text.toString()).coerceAtLeast(0)) { "any" }
    }

    private fun selectedSubtitleLanguage(): String {
        val labels = subtitleLanguageLabels()
        return subtitleValues.getOrElse(labels.indexOf(binding.subtitleLanguage.text.toString()).coerceAtLeast(0)) { "any" }
    }

    private fun movieLanguageLabels() = listOf(R.string.all_languages, R.string.arabic, R.string.english, R.string.hindi, R.string.turkish).map(::getString)

    private fun searchProviderLabels() = listOf(R.string.provider_tavily, R.string.provider_serper).map(::getString)

    private fun selectedSearchProvider(): String {
        val labels = searchProviderLabels()
        return searchProviderValues.getOrElse(labels.indexOf(binding.searchProvider.text.toString()).coerceAtLeast(0)) { "tavily" }
    }

    private fun subtitleLanguageLabels() = listOf(R.string.all_languages, R.string.arabic, R.string.english, R.string.turkish).map(::getString)

    private fun resultLimitLabels() = resultLimitValues.map { getString(R.string.result_count_value, it) }

    private fun selectedResultLimit(): Int {
        val labels = resultLimitLabels()
        return resultLimitValues.getOrElse(labels.indexOf(binding.resultLimit.text.toString()).coerceAtLeast(0)) { 10 }
    }

    private fun currentSearchDraft() = SearchDraft(
        query = binding.searchInput.text?.toString().orEmpty(),
        movieLanguage = selectedMovieLanguage(),
        subtitleLanguage = selectedSubtitleLanguage(),
        allowShortClips = binding.allowClips.isChecked,
        resultLimit = selectedResultLimit(),
        searchProvider = selectedSearchProvider(),
    )

    private fun saveSearchDraft() {
        if (::binding.isInitialized && !restoringSearchState) store.saveDraft(currentSearchDraft())
    }

    private fun applyDraft(draft: SearchDraft) {
        restoringSearchState = true
        try {
            val movieLabels = movieLanguageLabels()
            val providerLabels = searchProviderLabels()
            val subtitleLabels = subtitleLanguageLabels()
            val limitLabels = resultLimitLabels()
            binding.searchProvider.setText(providerLabels.getOrElse(searchProviderValues.indexOf(draft.searchProvider).coerceAtLeast(0)) { providerLabels.first() }, false)
            binding.movieLanguage.setText(movieLabels.getOrElse(movieValues.indexOf(draft.movieLanguage).coerceAtLeast(0)) { movieLabels.first() }, false)
            binding.subtitleLanguage.setText(subtitleLabels.getOrElse(subtitleValues.indexOf(draft.subtitleLanguage).coerceAtLeast(0)) { subtitleLabels.first() }, false)
            val normalizedLimit = draft.resultLimit.coerceIn(5, 30)
            val limitIndex = resultLimitValues.indexOf(normalizedLimit).takeIf { it >= 0 } ?: resultLimitValues.indexOf(10)
            binding.resultLimit.setText(limitLabels[limitIndex], false)
            binding.allowClips.isChecked = draft.allowShortClips
            binding.searchInput.setText(draft.query, false)
        } finally {
            restoringSearchState = false
        }
    }

    private fun restorePersistentState() {
        val searches = store.loadSearchHistory()
        val allMovies = unfilteredLibrary()
        refreshLibraryCategoryOptions(allMovies)
        val playback = filterLibrary(allMovies)
        searchHistoryAdapter.submit(searches)
        libraryAdapter.submit(playback)
        binding.emptySearchHistory.visibility = if (searches.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyLibrary.visibility = if (playback.isEmpty()) View.VISIBLE else View.GONE
        updateQuickAccessLabels()

        // Restore the latest completed result list independently from the live draft.
        // This way a query the user was still typing before the app/device closed is never
        // overwritten by an older completed search.
        searches.firstOrNull()?.let { latest ->
            showSearchResponse(latest.response, expand = !store.resultsCollapsed())
            binding.searchStatus.visibility = View.VISIBLE
            binding.searchStatus.setText(R.string.restored_results)
        }
        applyDraft(store.loadDraft())
    }

    private fun refreshHistoryLists() {
        val searches = store.loadSearchHistory()
        val allMovies = unfilteredLibrary()
        refreshLibraryCategoryOptions(allMovies)
        val playback = filterLibrary(allMovies)
        searchHistoryAdapter.submit(searches)
        libraryAdapter.submit(playback)
        binding.emptySearchHistory.visibility = if (searches.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyLibrary.visibility = if (playback.isEmpty()) View.VISIBLE else View.GONE
        updateQuickAccessLabels()
        if (::cinemaCenter.isInitialized) cinemaCenter.updateLibrary(allMovies, store.loadPlaybackHistory())
    }

    private fun unfilteredLibrary(): List<LibraryItem> =
        (serverCatalog + store.loadPlaybackHistory()).distinctBy { it.catalogId ?: it.uri }

    private fun availableLibraryCategories(): List<String> =
        unfilteredLibrary().flatMap { it.categories }.map(String::trim).filter(String::isNotEmpty).distinct().sorted()

    private fun filterLibrary(items: List<LibraryItem>): List<LibraryItem> = selectedLibraryCategory?.let { category ->
        items.filter { item -> item.categories.any { it.equals(category, ignoreCase = true) } }
    } ?: items

    private fun refreshLibraryCategoryOptions(items: List<LibraryItem>) {
        val categories = items.flatMap { it.categories }.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        if (selectedLibraryCategory != null && categories.none { it.equals(selectedLibraryCategory, true) }) selectedLibraryCategory = null
        val labels = listOf(getString(R.string.all_categories)) + categories
        binding.libraryCategoryFilter.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, labels))
        binding.libraryCategoryFilter.setText(selectedLibraryCategory ?: labels.first(), false)
    }

    private fun restoreSavedSearch(saved: SavedSearch, closeDrawer: Boolean = true) {
        applyDraft(saved.draft)
        showSearchResponse(saved.response, expand = !store.resultsCollapsed())
        binding.searchStatus.visibility = View.VISIBLE
        binding.searchStatus.text = saved.response.summary.ifBlank { getString(R.string.results) }
        store.saveDraft(saved.draft)
        if (closeDrawer) binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun showSearchResponse(response: SearchResponse, expand: Boolean) {
        resultsAdapter.submit(response.results)
        val count = resultsAdapter.count()
        binding.resultsCount.text = getString(R.string.playable_result_count, count)
        binding.resultsSection.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.searchStatus.visibility = View.VISIBLE
        binding.searchStatus.text = response.summary.ifBlank { getString(if (count == 0) R.string.no_results else R.string.results) }
        if (count > 0) setResultsCollapsed(!expand)
    }

    private fun setResultsCollapsed(collapsed: Boolean) {
        binding.resultsRecycler.visibility = if (collapsed) View.GONE else View.VISIBLE
        binding.resultsToggleButton.setText(if (collapsed) R.string.expand else R.string.collapse)
        store.setResultsCollapsed(collapsed)
        firebase.syncLocalState()
    }

    private fun setupPlayerControls() {
        binding.openFileButton.setOnClickListener { openVideo.launch(arrayOf("video/*", "audio/*")) }
        binding.openUrlButton.setOnClickListener { showUrlDialog() }
        binding.addSubtitleButton.setOnClickListener { openSubtitle.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain")) }
        binding.subtitleStyleButton.setOnClickListener { showSubtitleStyleDialog() }
        binding.adjustButton.setOnClickListener { showAdjustmentsDialog() }
        binding.orientationButton.setOnClickListener { toggleOrientation() }
        binding.castButton.setOnClickListener { openCastSettings() }
        binding.fullscreenButton.setOnClickListener { toggleFullscreen() }
        binding.playerFullscreenButton.setOnClickListener { toggleFullscreen() }
    }

    private fun setupFullscreenBackNavigation() {
        fullscreenBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    webCustomView != null -> hideWebCustomView()
                    fullscreen -> exitFullscreen()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, fullscreenBackCallback)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() = with(binding.embedView) {
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || callback == null || webCustomView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                if (fullscreen) exitFullscreen()
                webCustomView = view
                webCustomViewCallback = callback
                binding.fullscreenContainer.removeAllViews()
                (view.parent as? ViewGroup)?.removeView(view)
                binding.fullscreenContainer.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                binding.fullscreenContainer.visibility = View.VISIBLE
                setImmersivePlayerMode(true)
                fullscreenBackCallback.isEnabled = true
            }

            override fun onHideCustomView() = hideWebCustomView()
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                request?.let { observeEmbeddedMediaRequest(it.url.toString(), it.requestHeaders.orEmpty()) }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return false
                if (!request.isForMainFrame) return false
                val origin = currentEmbedOrigin?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
                val sourceHost = origin.host?.lowercase(Locale.ROOT) ?: return false
                val targetHost = target.host?.lowercase(Locale.ROOT) ?: return false
                val sameSite = targetHost == sourceHost || targetHost.endsWith(".$sourceHost") || sourceHost.endsWith(".$targetHost")
                if (sameSite) return false
                val targetText = (targetHost + target.path.orEmpty()).lowercase(Locale.ROOT)
                val playerLike = Regex("(?:player|embed|video|stream|server|watch|play)", RegexOption.IGNORE_CASE).containsMatchIn(targetText)
                return !playerLike
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view != null && !url.isNullOrBlank() && currentEmbedOrigin != null) scheduleWebPlayerDetection(view)
            }
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }
    }

    private fun observeEmbeddedMediaRequest(candidateUrl: String, requestHeaders: Map<String, String> = emptyMap()) {
        val origin = currentEmbedOrigin ?: return
        if (!supportsObservedMedia(origin)) return
        val lower = candidateUrl.lowercase(Locale.ROOT)
        if (!(lower.contains(".m3u8") || lower.contains("hls") || lower.contains("playlist") || lower.contains("manifest") || Regex("\\.(mp4|webm|m4v)(\\?|${'$'})").containsMatchIn(lower))) return
        synchronized(observedMediaRequests) {
            if (!observedMediaRequests.add(candidateUrl)) return
        }
        api.inspectMedia(candidateUrl, origin, requestHeaders) { result ->
            result.onSuccess { detected ->
                val playUrl = detected.hlsUrl ?: detected.playUrl
                if (currentEmbedOrigin != origin || !detected.playable || playUrl.isNullOrBlank() || detected.kind == "embed") return@onSuccess
                if (detected.hlsDrmProtected) {
                    Toast.makeText(this, R.string.webview_drm_detected, Toast.LENGTH_LONG).show()
                    return@onSuccess
                }
                val title = currentItem?.title ?: detected.title
                Toast.makeText(this, R.string.webview_stream_detected, Toast.LENGTH_SHORT).show()
                playLibraryItem(libraryItemFromResult(detected, title), rotateOnStart = false)
            }
        }
    }

    private fun scheduleWebPlayerDetection(view: WebView) {
        val generation = ++webAutoDetectGeneration
        listOf(900L, 3_500L, 12_000L, 20_000L).forEachIndexed { index, delay ->
            mainHandler.postDelayed({
                if (generation != webAutoDetectGeneration || currentEmbedOrigin == null || view != binding.embedView) return@postDelayed
                runWebPlayerDetection(view, clickControls = index > 0)
            }, delay)
        }
    }

    private fun runWebPlayerDetection(view: WebView, clickControls: Boolean) {
        val clickFlag = if (clickControls) "true" else "false"
        val script = """
            (() => {
              try {
                document.querySelectorAll('video').forEach(v => { try { v.muted = true; v.play().catch(() => {}); } catch (_) {} });
                if (!$clickFlag) return 'play-only';
                const label = /^(play|watch|start|server|تشغيل|مشاهدة|ابدأ|سيرفر)(\s*\d+)?${'$'}/i;
                const selectors = 'button,[role=\"button\"],a,.play,.play-button,[data-server],[data-player],[aria-label*=\"play\" i]';
                let clicked = 0;
                for (const el of document.querySelectorAll(selectors)) {
                  if (clicked >= 3) break;
                  const text = ((el.getAttribute('aria-label') || el.textContent || '') + '').trim().replace(/\s+/g, ' ');
                  const meta = ((el.id || '') + ' ' + (el.className || '')).toLowerCase();
                  const href = ((el.getAttribute('href') || '') + '').toLowerCase();
                  const suspicious = /(casino|bet|bonus|jackpot|slot|promo|advert|(^|[ _-])ad([ _-]|${'$'}))/.test((text + ' ' + meta + ' ' + href).toLowerCase());
                  if (suspicious) continue;
                  const semantic = label.test(text) || /(^|[ _-])(play|player|server|watch)([ _-]|${'$'})/.test(meta);
                  if (!semantic) continue;
                  const rect = el.getBoundingClientRect();
                  if (rect.width < 8 || rect.height < 8) continue;
                  try { el.click(); clicked++; } catch (_) {}
                }
                return String(clicked);
              } catch (_) { return '0'; }
            })();
        """.trimIndent()
        runCatching { view.evaluateJavascript(script, null) }
    }

    private fun supportsObservedMedia(origin: String): Boolean {
        val parsed = runCatching { Uri.parse(origin) }.getOrNull() ?: return false
        val host = parsed.host?.lowercase(Locale.ROOT) ?: return false
        return parsed.scheme in listOf("http", "https") && host.contains('.')
    }

    private fun setupFeatureCards() {
        binding.featurePicture.featureTitle.setText(R.string.feature_picture_title)
        binding.featurePicture.featureText.setText(R.string.feature_picture_text)
        binding.featureSubtitle.featureTitle.setText(R.string.feature_subtitle_title)
        binding.featureSubtitle.featureText.setText(R.string.feature_subtitle_text)
        binding.featureBackground.featureTitle.setText(R.string.feature_background_title)
        binding.featureBackground.featureText.setText(R.string.feature_background_text)
    }

    private fun connectPlayerService() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    binding.playerView.player = mediaController
                    mediaController.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            binding.playerInfoBadge.alpha = if (playbackState == Player.STATE_BUFFERING) 0.72f else 1f
                            if (playbackState == Player.STATE_ENDED) persistPersonalProgress()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            binding.playerView.keepScreenOn = isPlaying
                            if (!isPlaying) persistPersonalProgress()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Toast.makeText(this@MainActivity, R.string.playback_error, Toast.LENGTH_LONG).show()
                        }
                    })
                    pendingPlayback?.also { pendingPlayback = null; playLibraryItem(it, rotateOnStart = false) }
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun openResult(result: SearchResult) {
        val playUrl = result.hlsUrl ?: result.playUrl
        if (result.playable && !playUrl.isNullOrBlank()) {
            playLibraryItem(libraryItemFromResult(result))
        } else {
            Toast.makeText(this, R.string.direct_only, Toast.LENGTH_SHORT).show()
        }
    }

    private fun libraryItemFromResult(result: SearchResult, titleOverride: String? = null): LibraryItem {
        val playUrl = result.hlsUrl ?: result.playUrl.orEmpty()
        return LibraryItem(
            title = titleOverride ?: result.title,
            uri = playUrl,
            kind = result.kind ?: "video",
            source = result.provider,
            pageUrl = result.url,
            contentType = result.contentType,
            downloadable = result.downloadable,
            downloadUrl = result.downloadUrl,
            detectedBy = result.detectedBy,
            hlsMaster = result.hlsMaster,
            hlsVariantCount = result.hlsVariantCount,
            hlsAudioRenditionCount = result.hlsAudioRenditionCount,
            hlsSubtitleRenditionCount = result.hlsSubtitleRenditionCount,
            subtitleLanguages = result.subtitleLanguages,
            subtitleEvidence = result.subtitleEvidence,
            hlsDurationSeconds = result.hlsDurationSeconds,
            hlsLive = result.hlsLive,
            hlsEncrypted = result.hlsEncrypted,
            hlsDrmProtected = result.hlsDrmProtected,
            playbackHeaders = result.playbackHeaders,
        )
    }

    private fun downloadResult(result: SearchResult) {
        val value = result.downloadUrl?.takeIf { result.downloadable && (it.startsWith("https://") || it.startsWith("http://")) }
        if (!value.isNullOrBlank()) firebase.logSave(result.title, "video")
        enqueueVideoDownload(result.title, result.provider, value)
    }

    private fun enqueueVideoDownload(title: String, source: String, value: String?) {
        if (!canUseNetwork { enqueueVideoDownload(title, source, value) }) return
        if (value.isNullOrBlank()) {
            Toast.makeText(this, R.string.download_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val parsed = Uri.parse(value)
        val rawExtension = parsed.lastPathSegment?.substringAfterLast('.', "mp4")?.substringBefore('?')?.lowercase(Locale.ROOT)
        val extension = rawExtension?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "mp4"
        val safeTitle = title.replace(Regex("[^\\p{L}\\p{N}._ -]+"), "").trim().take(80).ifBlank { "movie" }
        val request = DownloadManager.Request(parsed)
            .setTitle(title)
            .setDescription(source)
            .setMimeType("video/*")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MOVIES, "$safeTitle.$extension")
        val manager = getSystemService(DownloadManager::class.java)
        runCatching { manager.enqueue(request) }
            .onSuccess { Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, R.string.download_unavailable, Toast.LENGTH_LONG).show() }
    }

    private fun exportAudio(title: String, value: String?) {
        if (!canUseNetwork { exportAudio(title, value) }) return
        if (value.isNullOrBlank() || !(value.startsWith("https://") || value.startsWith("http://"))) {
            Toast.makeText(this, R.string.audio_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.audio_export_started, Toast.LENGTH_SHORT).show()
        firebase.logSave(title, "audio")
        audioExporter.export(value, title) { result ->
            Toast.makeText(this, if (result.isSuccess) R.string.audio_export_done else R.string.audio_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openLibraryItem(item: LibraryItem) {
        playLibraryItem(item)
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun playLibraryItem(item: LibraryItem, rotateOnStart: Boolean = true) {
        if (item.uri.startsWith("http") && !canUseNetwork { playLibraryItem(item, rotateOnStart) }) return
        if (rotateOnStart) maybeEnterLandscapeForPlayback()
        currentItem = item
        if (!item.catalogId.isNullOrBlank()) personalLibrary.upsertCatalogItem(item, watchlist = false)
        binding.nowPlayingTitle.text = item.title
        binding.playerInfoBadge.visibility = View.VISIBLE
        binding.playerInfoBadge.text = when (item.kind) {
            "hls" -> getString(R.string.stream_hls)
            "embed" -> getString(R.string.stream_embed)
            else -> getString(R.string.stream_video)
        }
        binding.emptyLibrary.visibility = View.GONE
        libraryAdapter.add(item)
        storageExecutor.execute {
            store.savePlayback(item)
            firebase.syncLocalState()
            runOnUiThread { refreshHistoryLists() }
        }
        enrichPlayingMovie(item)
        firebase.logPlayback(item)
        binding.playerCard.alpha = 0.65f
        binding.playerCard.animate().alpha(1f).setDuration(300).start()
        if (item.kind == "embed" && isTrustedEmbed(item.uri)) {
            controller?.pause()
            PlaybackHeaders.clear()
            webAutoDetectGeneration += 1
            currentEmbedOrigin = item.pageUrl ?: item.uri
            synchronized(observedMediaRequests) { observedMediaRequests.clear() }
            binding.playerView.visibility = View.GONE
            binding.embedView.visibility = View.VISIBLE
            binding.embedView.loadUrl(item.uri)
            return
        }
        currentEmbedOrigin = null
        binding.embedView.stopLoading()
        binding.embedView.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        val activeController = controller
        if (activeController == null) {
            pendingPlayback = item
            return
        }
        PlaybackHeaders.set(item.playbackHeaders)
        val mediaItem = buildMediaItem(item)
        activeController.setMediaItem(mediaItem)
        activeController.prepare()
        activeController.play()
    }

    private fun isTrustedEmbed(value: String): Boolean {
        val parsed = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        val host = parsed.host?.lowercase(Locale.ROOT) ?: return false
        if (parsed.scheme !in listOf("http", "https")) return false
        if (!host.contains('.') || host == "localhost" || host.endsWith(".local")) return false
        return true
    }

    private fun buildMediaItem(item: LibraryItem): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(item.uri)
            .setMediaId(item.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(item.source).build())
        subtitleUri?.let { uri ->
            val mime = if (uri.toString().lowercase(Locale.ROOT).endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            builder.setSubtitleConfigurations(
                listOf(MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()),
            )
        }
        return builder.build()
    }

    private fun showResultDetails(result: SearchResult) = openMovieDetails(libraryItemFromResult(result))

    private fun showLibraryDetails(item: LibraryItem) = openMovieDetails(item)

    private fun openMovieDetails(item: LibraryItem) {
        movieDetails.launch(MovieDetailsActivity.intent(this, item))
    }

    private fun showMediaDetails(item: LibraryItem) {
        val dialog = BottomSheetDialog(this)
        val content = dialogContainer(R.string.media_details)
        content.addView(TextView(this).apply {
            text = item.title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setPadding(0, 0, 0, dp(10))
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = buildMediaDetails(item)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.matte_on_surface_variant))
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }, matchWrap())
        content.addView(MaterialButton(this).apply {
            setText(R.string.play)
            setOnClickListener { dialog.dismiss(); playLibraryItem(item) }
        }, matchWrap())
        if (item.downloadable && !item.downloadUrl.isNullOrBlank()) {
            content.addView(MaterialButton(this).apply {
                setText(R.string.save_video)
                setOnClickListener { enqueueVideoDownload(item.title, item.source, item.downloadUrl) }
            }, matchWrap())
            content.addView(MaterialButton(this).apply {
                setText(R.string.save_audio)
                setOnClickListener { exportAudio(item.title, item.downloadUrl) }
            }, matchWrap())
        }
        content.addView(MaterialButton(this).apply {
            setText(R.string.done)
            setOnClickListener { dialog.dismiss() }
        }, matchWrap())
        dialog.setContentView(wrapInScroll(content))
        dialog.show()
    }

    private fun buildMediaDetails(item: LibraryItem): String = buildString {
        appendLine(getString(R.string.detail_provider, item.source))
        appendLine(getString(R.string.detail_type, streamLabel(item.kind)))
        item.detectedBy?.let { appendLine(getString(R.string.detail_detection, detectionLabel(it))) }
        if (item.hlsVariantCount > 0) appendLine(getString(R.string.detail_variants, item.hlsVariantCount))
        if (item.hlsAudioRenditionCount > 0) appendLine(getString(R.string.detail_audio_tracks, item.hlsAudioRenditionCount))
        if (item.hlsSubtitleRenditionCount > 0 || item.subtitleLanguages.isNotEmpty()) {
            val labels = item.subtitleLanguages.joinToString(", ") { subtitleLanguageName(it) }
            appendLine(getString(R.string.detail_subtitles, labels.ifBlank { item.hlsSubtitleRenditionCount.toString() }))
        }
        item.subtitleEvidence?.let { appendLine(getString(R.string.detail_subtitle_evidence, subtitleEvidenceLabel(it))) }
        if (item.hlsDurationSeconds > 0) appendLine(getString(R.string.detail_duration, (item.hlsDurationSeconds / 60).coerceAtLeast(1)))
        if (item.hlsLive) appendLine(getString(R.string.detail_live))
        if (item.hlsDrmProtected) appendLine(getString(R.string.detail_drm_protected))
        else if (item.hlsEncrypted) appendLine(getString(R.string.detail_encrypted))
        if (item.playbackHeaders.isNotEmpty()) appendLine(getString(R.string.detail_playback_headers, item.playbackHeaders.keys.joinToString(", ")))
        if (item.downloadable && !item.downloadUrl.isNullOrBlank()) appendLine(getString(R.string.detail_direct_download))
        appendLine()
        append(getString(R.string.detail_url, item.pageUrl ?: item.uri))
    }

    private fun subtitleLanguageName(value: String) = when (value.lowercase(Locale.ROOT)) {
        "ar" -> getString(R.string.arabic)
        "en" -> getString(R.string.english)
        "tr" -> getString(R.string.turkish)
        else -> value.uppercase(Locale.ROOT)
    }

    private fun subtitleEvidenceLabel(value: String) = when (value) {
        "manifest" -> "HLS manifest"
        "track" -> "subtitle track"
        "page_text" -> "page metadata"
        else -> value
    }

    private fun streamLabel(kind: String) = when (kind) {
        "hls" -> getString(R.string.stream_hls)
        "embed" -> getString(R.string.stream_embed)
        else -> getString(R.string.stream_video)
    }

    private fun detectionLabel(value: String) = when (value) {
        "direct_url" -> "Direct URL"
        "content_type" -> "Content-Type"
        "html_manifest" -> "HTML / M3U8"
        "html_media" -> "HTML media"
        "provider_api" -> "Provider API"
        "webview_candidate" -> "WebView scan candidate"
        "webview_observed" -> "WebView network"
        else -> value
    }

    private fun showUrlDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val url = TextInputEditText(this).apply { hint = getString(R.string.url_hint); inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val title = TextInputEditText(this).apply { hint = getString(R.string.movie_title_hint) }
        container.addView(url, matchWrap())
        container.addView(title, matchWrap())
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.url_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.play, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = url.text?.toString()?.trim().orEmpty()
                val parsed = runCatching { Uri.parse(value) }.getOrNull()
                if (parsed?.scheme !in listOf("http", "https", "content")) { url.error = getString(R.string.url_hint); return@setOnClickListener }
                val safeParsed = parsed ?: return@setOnClickListener
                val displayTitle = title.text?.toString()?.ifBlank { safeParsed.host ?: getString(R.string.open_url) } ?: getString(R.string.open_url)
                val knownKind = when {
                    value.contains("/embed/") || value.contains("youtube.com") || value.contains("youtu.be") || value.contains("player.vimeo.com") -> "embed"
                    value.contains(".m3u8", ignoreCase = true) -> "hls"
                    Regex("\\.(mp4|webm|m4v|mov)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(value) -> "video"
                    else -> null
                }
                dialog.dismiss()
                if (knownKind != null) {
                    playLibraryItem(LibraryItem(displayTitle, value, knownKind, safeParsed.host.orEmpty()))
                } else {
                    Toast.makeText(this, R.string.detecting_stream, Toast.LENGTH_SHORT).show()
                    api.inspectMedia(value) { result ->
                        result.onSuccess { detected ->
                            if (detected.playable) openResult(detected)
                            else Toast.makeText(this, R.string.no_results, Toast.LENGTH_SHORT).show()
                        }.onFailure { Toast.makeText(this, R.string.search_failed, Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showSubtitleStyleDialog() {
        val dialog = BottomSheetDialog(this)
        val content = dialogContainer(R.string.subtitle_style_title)
        val sizeBar = addSlider(content, R.string.font_size, 14, 48, subtitlePrefs.sizeSp) { subtitlePrefs.sizeSp = it; applySubtitleStyle() }
        val opacityBar = addSlider(content, R.string.background_opacity, 0, 100, subtitlePrefs.opacity) { subtitlePrefs.opacity = it; applySubtitleStyle() }
        val positionBar = addSlider(content, R.string.subtitle_position, 4, 42, subtitlePrefs.bottomPosition) { subtitlePrefs.bottomPosition = it; applySubtitleStyle() }
        addColorChoices(content, R.string.font_color, listOf(Color.WHITE, 0xFFFFE9A8.toInt(), 0xFFD8E2FF.toInt(), 0xFF111111.toInt())) { subtitlePrefs.foreground = it; applySubtitleStyle() }
        addColorChoices(content, R.string.background_color, listOf(Color.BLACK, 0xFF15182B.toInt(), 0xFF4C171C.toInt(), 0xFFE0D3C0.toInt())) { subtitlePrefs.background = it; applySubtitleStyle() }
        val boldSwitch = MaterialSwitch(this).apply { setText(R.string.bold_text); isChecked = subtitlePrefs.bold; setOnCheckedChangeListener { _, checked -> subtitlePrefs.bold = checked; applySubtitleStyle() } }
        val shadowSwitch = MaterialSwitch(this).apply { setText(R.string.text_shadow); isChecked = subtitlePrefs.shadow; setOnCheckedChangeListener { _, checked -> subtitlePrefs.shadow = checked; applySubtitleStyle() } }
        content.addView(boldSwitch, matchWrap()); content.addView(shadowSwitch, matchWrap())
        content.addView(MaterialButton(this).apply {
            setText(R.string.reset)
            setOnClickListener {
                subtitlePrefs.sizeSp = 22; subtitlePrefs.opacity = 72; subtitlePrefs.bottomPosition = 12
                subtitlePrefs.foreground = Color.WHITE; subtitlePrefs.background = Color.BLACK
                subtitlePrefs.bold = true; subtitlePrefs.shadow = true
                sizeBar.progress = 22 - 14; opacityBar.progress = 72; positionBar.progress = 12 - 4
                boldSwitch.isChecked = true; shadowSwitch.isChecked = true
                applySubtitleStyle()
            }
        }, matchWrap())
        dialog.setContentView(wrapInScroll(content))
        dialog.show()
    }

    private fun applySubtitleStyle() {
        val subtitleView = binding.playerView.subtitleView ?: return
        subtitleView.setApplyEmbeddedStyles(false)
        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitlePrefs.sizeSp.toFloat())
        subtitleView.setBottomPaddingFraction(subtitlePrefs.bottomPosition / 100f)
        subtitleView.setStyle(
            CaptionStyleCompat(
                subtitlePrefs.foreground,
                ColorUtils.setAlphaComponent(subtitlePrefs.background, (subtitlePrefs.opacity * 2.55f).toInt()),
                Color.TRANSPARENT,
                if (subtitlePrefs.shadow) CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW else CaptionStyleCompat.EDGE_TYPE_NONE,
                Color.BLACK,
                if (subtitlePrefs.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
            ),
        )
    }

    private fun showAdjustmentsDialog() {
        val dialog = BottomSheetDialog(this)
        val content = dialogContainer(R.string.adjust_title)
        val sliders = linkedMapOf<String, SeekBar>()
        content.addView(MaterialButton(this).apply {
            setText(R.string.auto_adjust)
            setOnClickListener {
                picture.apply { temperature = 2; tint = 0; brightness = 1; contrast = -6; highlights = -5; shadows = 4; vibrance = -4; saturation = -10; sharpness = 0; clarity = -2; vignette = 4; zoom = 100; crop = false }
                updateAdjustmentSliders(sliders); applyPictureAdjustments()
            }
        }, matchWrap())
        addSection(content, R.string.white_balance)
        sliders["temperature"] = addSignedSlider(content, R.string.temperature, picture.temperature) { picture.temperature = it; applyPictureAdjustments() }
        sliders["tint"] = addSignedSlider(content, R.string.tint, picture.tint) { picture.tint = it; applyPictureAdjustments() }
        addSection(content, R.string.light)
        sliders["brightness"] = addSignedSlider(content, R.string.brightness, picture.brightness) { picture.brightness = it; applyPictureAdjustments() }
        sliders["contrast"] = addSignedSlider(content, R.string.contrast, picture.contrast) { picture.contrast = it; applyPictureAdjustments() }
        sliders["highlights"] = addSignedSlider(content, R.string.highlights, picture.highlights) { picture.highlights = it; applyPictureAdjustments() }
        sliders["shadows"] = addSignedSlider(content, R.string.shadows, picture.shadows) { picture.shadows = it; applyPictureAdjustments() }
        addSection(content, R.string.color_group)
        sliders["vibrance"] = addSignedSlider(content, R.string.vibrance, picture.vibrance) { picture.vibrance = it; applyPictureAdjustments() }
        sliders["saturation"] = addSignedSlider(content, R.string.saturation, picture.saturation) { picture.saturation = it; applyPictureAdjustments() }
        addSection(content, R.string.texture)
        sliders["sharpness"] = addSignedSlider(content, R.string.sharpness, picture.sharpness) { picture.sharpness = it; applyPictureAdjustments() }
        sliders["clarity"] = addSignedSlider(content, R.string.clarity, picture.clarity) { picture.clarity = it; applyPictureAdjustments() }
        sliders["vignette"] = addSignedSlider(content, R.string.vignette, picture.vignette) { picture.vignette = max(0, it); applyPictureAdjustments() }
        sliders["zoom"] = addSlider(content, R.string.zoom, 100, 160, picture.zoom) { picture.zoom = it; applyPictureAdjustments() }
        content.addView(MaterialSwitch(this).apply {
            text = getString(R.string.crop_mode)
            isChecked = picture.crop
            setOnCheckedChangeListener { _, checked -> picture.crop = checked; applyPictureAdjustments() }
        }, matchWrap())
        content.addView(MaterialButton(this).apply {
            setText(R.string.reset_adjustments)
            setOnClickListener { picture.apply { temperature = 0; tint = 0; brightness = 0; contrast = 0; highlights = 0; shadows = 0; vibrance = 0; saturation = 0; sharpness = 0; clarity = 0; vignette = 0; zoom = 100; crop = false }; updateAdjustmentSliders(sliders); applyPictureAdjustments() }
        }, matchWrap())
        dialog.setContentView(wrapInScroll(content))
        dialog.show()
    }

    private fun updateAdjustmentSliders(sliders: Map<String, SeekBar>) {
        sliders["temperature"]?.progress = picture.temperature + 100; sliders["tint"]?.progress = picture.tint + 100
        sliders["brightness"]?.progress = picture.brightness + 100; sliders["contrast"]?.progress = picture.contrast + 100
        sliders["highlights"]?.progress = picture.highlights + 100; sliders["shadows"]?.progress = picture.shadows + 100
        sliders["vibrance"]?.progress = picture.vibrance + 100; sliders["saturation"]?.progress = picture.saturation + 100
        sliders["sharpness"]?.progress = picture.sharpness + 100; sliders["clarity"]?.progress = picture.clarity + 100
        sliders["vignette"]?.progress = picture.vignette + 100; sliders["zoom"]?.progress = picture.zoom - 100
    }

    private fun applyPictureAdjustments() {
        val surface = binding.playerView.videoSurfaceView ?: return
        val saturation = (1f + (picture.saturation + picture.vibrance * .35f) / 100f).coerceIn(0f, 2f)
        val contrast = (1f + (picture.contrast + picture.clarity * .35f + picture.sharpness * .12f) / 100f).coerceIn(.2f, 2f)
        val brightness = (picture.brightness + picture.shadows * .22f + picture.highlights * .12f) * 1.25f
        val temperature = picture.temperature / 500f
        val tint = picture.tint / 700f
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        matrix.postConcat(ColorMatrix(floatArrayOf(
            contrast + temperature, 0f, 0f, 0f, brightness,
            0f, contrast + tint, 0f, 0f, brightness,
            0f, 0f, contrast - temperature, 0f, brightness,
            0f, 0f, 0f, 1f, 0f,
        )))
        surface.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        val scale = picture.zoom / 100f
        surface.scaleX = scale; surface.scaleY = scale
        binding.playerView.resizeMode = if (picture.crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        binding.vignetteOverlay.apply {
            visibility = if (picture.vignette > 0) View.VISIBLE else View.GONE
            alpha = (picture.vignette / 100f).coerceIn(0f, .75f)
            post {
                background = GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    gradientRadius = max(width, height) * .72f
                    colors = intArrayOf(Color.TRANSPARENT, 0x22000000, 0xDD000000.toInt())
                }
            }
        }
    }

    private fun addSection(parent: LinearLayout, text: Int) {
        parent.addView(TextView(this).apply { setText(text); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium); setPadding(0, dp(22), 0, dp(4)) }, matchWrap())
    }

    private fun addSignedSlider(parent: LinearLayout, label: Int, value: Int, onChange: (Int) -> Unit) =
        addSlider(parent, label, -100, 100, value, onChange)

    private fun addSlider(parent: LinearLayout, label: Int, minValue: Int, maxValue: Int, value: Int, onChange: (Int) -> Unit): SeekBar {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val title = TextView(this).apply { text = getString(label); setTextColor(ContextCompat.getColor(this@MainActivity, R.color.matte_on_surface)) }
        val valueText = TextView(this).apply { text = value.toString(); gravity = Gravity.END; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.matte_on_surface_variant)) }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(valueText) }
        val seek = SeekBar(this).apply {
            max = maxValue - minValue
            progress = value - minValue
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { val actual = progress + minValue; valueText.text = actual.toString(); if (fromUser) onChange(actual) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        row.addView(heading, matchWrap()); row.addView(seek, matchWrap()); parent.addView(row, matchWrap())
        return seek
    }

    private fun addColorChoices(parent: LinearLayout, label: Int, colors: List<Int>, onSelect: (Int) -> Unit) {
        addSection(parent, label)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
        colors.forEach { color ->
            row.addView(MaterialButton(this).apply {
                text = " "
                backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.matte_outline))
                strokeWidth = dp(1)
                setOnClickListener { onSelect(color) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        }
        parent.addView(row, matchWrap())
    }

    private fun dialogContainer(title: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(14), dp(22), dp(32))
        addView(TextView(this@MainActivity).apply { setText(title); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall); setPadding(0, dp(4), 0, dp(12)) }, matchWrap())
    }

    private fun wrapInScroll(content: View) = androidx.core.widget.NestedScrollView(this).apply {
        isFillViewport = true
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun showCellularWarning() {
        if (cellularDialogShown || mobileDataApproved || isFinishing) return
        cellularDialogShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cellular_title)
            .setMessage(R.string.cellular_message)
            .setNegativeButton(R.string.wait_for_wifi) { _, _ -> mobileDataApproved = false; pendingCellularAction = null }
            .setPositiveButton(R.string.continue_anyway) { _, _ ->
                mobileDataApproved = true
                pendingCellularAction?.also { pendingCellularAction = null; it() }
            }
            .show()
    }

    private fun canUseNetwork(onApproved: (() -> Unit)? = null): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return true
        val cellularOnly = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        if (cellularOnly && !mobileDataApproved) {
            if (onApproved != null) pendingCellularAction = onApproved
            showCellularWarning()
            return false
        }
        return true
    }

    private fun openCastSettings() {
        Toast.makeText(this, R.string.cast_unavailable, Toast.LENGTH_SHORT).show()
        runCatching { startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }
            .recoverCatching { startActivity(Intent("android.settings.WIFI_DISPLAY_SETTINGS")) }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("appearance", MODE_PRIVATE)
        val dialog = BottomSheetDialog(this)
        val content = dialogContainer(R.string.settings)

        addSection(content, R.string.appearance)
        val themeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val themeOptions = listOf(
            "system" to R.string.theme_system,
            "light" to R.string.theme_light,
            "dark" to R.string.theme_dark,
        )
        val currentTheme = prefs.getString("theme_mode", "dark") ?: "dark"
        val themeButtons = themeOptions.associate { (value, label) ->
            value to RadioButton(this).apply {
                id = View.generateViewId()
                setText(label)
                isChecked = value == currentTheme
                setPadding(dp(4), dp(7), dp(4), dp(7))
                themeGroup.addView(this, matchWrap())
            }
        }
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            themeButtons.entries.firstOrNull { it.value.id == checkedId }?.key?.let { mode ->
                if (mode != prefs.getString("theme_mode", "dark")) {
                    setThemeMode(mode)
                    dialog.dismiss()
                }
            }
        }
        content.addView(themeGroup, matchWrap())

        addSection(content, R.string.privacy)
        content.addView(MaterialSwitch(this).apply {
            setText(R.string.usage_analytics)
            isChecked = prefs.getBoolean("analytics_enabled", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("analytics_enabled", checked).apply()
                firebase.setAnalyticsEnabled(checked)
            }
        }, matchWrap())

        addSection(content, R.string.playback_orientation)
        val orientationGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val orientationOptions = listOf(
            "auto" to R.string.orientation_auto,
            "portrait" to R.string.orientation_portrait,
            "landscape" to R.string.orientation_landscape,
        )
        val currentOrientation = prefs.getString("orientation_mode", "auto") ?: "auto"
        val orientationButtons = orientationOptions.associate { (value, label) ->
            value to RadioButton(this).apply {
                id = View.generateViewId()
                setText(label)
                isChecked = value == currentOrientation
                setPadding(dp(4), dp(7), dp(4), dp(7))
                orientationGroup.addView(this, matchWrap())
            }
        }
        orientationGroup.setOnCheckedChangeListener { _, checkedId ->
            orientationButtons.entries.firstOrNull { it.value.id == checkedId }?.key?.let(::setOrientationMode)
        }
        content.addView(orientationGroup, matchWrap())

        content.addView(MaterialSwitch(this).apply {
            setText(R.string.auto_landscape)
            isChecked = prefs.getBoolean("auto_landscape", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("auto_landscape", checked).apply() }
        }, matchWrap())
        content.addView(MaterialButton(this).apply {
            setText(R.string.language_toggle)
            setOnClickListener { dialog.dismiss(); toggleLanguage() }
        }, matchWrap())
        dialog.setContentView(wrapInScroll(content))
        dialog.show()
    }

    private fun toggleTheme() {
        val currentlyDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setThemeMode(if (currentlyDark) "light" else "dark")
    }

    private fun setThemeMode(mode: String) {
        getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("theme_mode", mode).apply()
        firebase.logTheme(mode)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    private fun applySavedOrientation() {
        val mode = getSharedPreferences("appearance", MODE_PRIVATE).getString("orientation_mode", "auto") ?: "auto"
        requestedOrientation = when (mode) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun setOrientationMode(mode: String) {
        getSharedPreferences("appearance", MODE_PRIVATE).edit().putString("orientation_mode", mode).apply()
        applySavedOrientation()
    }

    private fun maybeEnterLandscapeForPlayback() {
        val prefs = getSharedPreferences("appearance", MODE_PRIVATE)
        if (prefs.getBoolean("auto_landscape", true) && prefs.getString("orientation_mode", "auto") == "auto") {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        setOrientationMode(if (landscape) "portrait" else "landscape")
    }

    private fun toggleLanguage() {
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { Locale.getDefault().language }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if (current.startsWith("ar")) "en" else "ar"))
    }

    private fun toggleFullscreen() {
        if (fullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        if (fullscreen || webCustomView != null) return
        fullscreen = true
        binding.drawerLayout.closeDrawers()
        val frame = binding.playerFrame
        (frame.parent as? ViewGroup)?.removeView(frame)
        binding.fullscreenContainer.removeAllViews()
        binding.fullscreenContainer.addView(
            frame,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        binding.fullscreenContainer.visibility = View.VISIBLE
        binding.playerFullscreenButton.setIconResource(R.drawable.ic_fullscreen_exit)
        binding.playerFullscreenButton.contentDescription = getString(R.string.exit_fullscreen)
        binding.fullscreenButton.setText(R.string.exit_fullscreen)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setImmersivePlayerMode(true)
        fullscreenBackCallback.isEnabled = true
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        fullscreen = false
        val frame = binding.playerFrame
        (frame.parent as? ViewGroup)?.removeView(frame)
        binding.playerCard.removeAllViews()
        binding.playerCard.addView(
            frame,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        binding.fullscreenContainer.visibility = View.GONE
        binding.playerFullscreenButton.setIconResource(R.drawable.ic_fullscreen)
        binding.playerFullscreenButton.contentDescription = getString(R.string.fullscreen)
        binding.fullscreenButton.setText(R.string.fullscreen)
        applySavedOrientation()
        setImmersivePlayerMode(false)
        fullscreenBackCallback.isEnabled = webCustomView != null
    }

    private fun setImmersivePlayerMode(enabled: Boolean) {
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            insets.hide(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            insets.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun hideWebCustomView() {
        val view = webCustomView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        binding.fullscreenContainer.removeAllViews()
        binding.fullscreenContainer.visibility = View.GONE
        webCustomView = null
        webCustomViewCallback?.onCustomViewHidden()
        webCustomViewCallback = null
        setImmersivePlayerMode(false)
        applySavedOrientation()
        fullscreenBackCallback.isEnabled = fullscreen
    }

    private fun setupPictureInPicture() {
        if (Build.VERSION.SDK_INT < 31) return
        binding.playerCard.doOnLayout { view ->
            val source = Rect()
            view.getGlobalVisibleRect(source)
            setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setSourceRectHint(source)
                    .setAutoEnterEnabled(controller?.isPlaying == true)
                    .build(),
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && controller?.isPlaying == true && !isInPictureInPictureMode) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        }
    }

    private fun persistPersonalProgress() {
        val item = currentItem ?: return
        val active = controller ?: return
        if (active.duration <= 0L || active.currentPosition < 0L) return
        item.catalogId?.let { personalLibrary.setProgress(it, active.currentPosition, active.duration) }
        val historyPrefs = getSharedPreferences("global_history", MODE_PRIVATE)
        if (historyPrefs.getBoolean("consent", false)) {
            api.recordPlaybackHistory(historyVisitorId, item, active.currentPosition, active.duration)
        } else if (!historyPrefs.getBoolean("consent_asked", false)) {
            historyPrefs.edit().putBoolean("consent_asked", true).apply()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.global_history_consent_title)
                .setMessage(R.string.global_history_consent_message)
                .setPositiveButton(R.string.global_history_consent_allow) { _, _ ->
                    historyPrefs.edit().putBoolean("consent", true).apply()
                    api.recordPlaybackHistory(historyVisitorId, item, active.currentPosition, active.duration)
                }
                .setNegativeButton(R.string.global_history_consent_deny, null)
                .show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun playbackItemFromIntent(source: Intent?): LibraryItem? {
        if (source?.getBooleanExtra("dashboard_play", false) != true) return null
        val uri = source.getStringExtra("dashboard_uri").orEmpty()
        if (uri.isBlank()) return null
        return LibraryItem(
            title = source.getStringExtra("dashboard_title").orEmpty(),
            uri = uri,
            kind = source.getStringExtra("dashboard_kind") ?: if (uri.contains(".m3u8", true)) "hls" else "video",
            source = source.getStringExtra("dashboard_source") ?: "Catalog",
            pageUrl = source.getStringExtra("dashboard_page_url"),
            catalogId = source.getStringExtra("dashboard_catalog_id"),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        playbackItemFromIntent(intent)?.let { playLibraryItem(it, rotateOnStart = false) }
    }

    private fun openExternal(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun documentName(uri: Uri): String? = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onStop() {
        persistPersonalProgress()
        // Queue the latest local snapshot immediately, but never block the UI thread.
        firebase.flushLocalState()
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        suggestionJob?.let(mainHandler::removeCallbacks)
        binding.playerView.player = null
        controller?.release()
        controller = null
        api.close()
        firebase.stop()
        personalLibrary.stop()
        storageExecutor.shutdown()
        audioExporter.close()
        binding.embedView.destroy()
        super.onDestroy()
    }
}

private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed(s?.toString().orEmpty())
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
