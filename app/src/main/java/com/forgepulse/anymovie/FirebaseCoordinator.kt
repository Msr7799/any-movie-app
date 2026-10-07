package com.forgepulse.anymovie

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * Optional Firebase layer.
 *
 * Core app usage never depends on authentication. When Firebase is configured we
 * create an anonymous session in the background, then optionally allow the user
 * to link Google or Email/Password later. Realtime Database work is deliberately
 * serialized and de-bounced off the UI thread so large search/playback histories
 * cannot freeze the Activity while they are merged or uploaded.
 */
class FirebaseCoordinator(
    private val activity: ComponentActivity,
    private val store: AppStore,
) {
    data class AccountState(
        val configured: Boolean,
        val uid: String? = null,
        val anonymous: Boolean = true,
        val displayName: String? = null,
        val email: String? = null,
        val provider: String = "anonymous",
        val cloudSyncActive: Boolean = false,
    )

    private val credentialManager by lazy { CredentialManager.create(activity) }
    private val syncExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "AnyMovie-FirebaseSync").apply { isDaemon = true }
    }

    private var auth: FirebaseAuth? = null
    private var database: FirebaseDatabase? = null
    private var analytics: FirebaseAnalytics? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var stateReference: DatabaseReference? = null
    private var stateListener: ValueEventListener? = null
    private var stateListenerUid: String? = null
    private var catalogReference: DatabaseReference? = null
    private var catalogListener: ValueEventListener? = null
    private var catalogCallback: ((List<LibraryItem>) -> Unit)? = null
    private var accountCallback: ((AccountState) -> Unit)? = null
    private var restoredCallback: (() -> Unit)? = null
    private var lastAccountFingerprint: String? = null
    private var pendingSync: ScheduledFuture<*>? = null

    @Volatile private var lastUploadedStateFingerprint: String? = null
    @Volatile private var stopped = false

    val configured: Boolean
        get() = FirebaseApp.getApps(activity).isNotEmpty()

    fun start(
        analyticsEnabled: Boolean,
        onAccountChanged: (AccountState) -> Unit,
        onCloudStateRestored: () -> Unit,
        onPublicCatalogChanged: (List<LibraryItem>) -> Unit = {},
    ) {
        stopped = false
        accountCallback = onAccountChanged
        restoredCallback = onCloudStateRestored
        catalogCallback = onPublicCatalogChanged
        if (!configured) {
            onAccountChanged(AccountState(configured = false))
            return
        }

        auth = FirebaseAuth.getInstance()
        analytics = FirebaseAnalytics.getInstance(activity).also {
            it.setAnalyticsCollectionEnabled(analyticsEnabled)
        }
        database = runCatching { FirebaseDatabase.getInstance() }.getOrNull()?.also { db ->
            // Firebase only allows this before the first reference is used.
            runCatching { db.setPersistenceEnabled(true) }
        }

        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user == null) {
                detachRealtimeStateListener()
                // Local guest mode remains fully usable even if Anonymous Auth is
                // disabled/misconfigured in Firebase Console.
                publish(AccountState(configured = true, cloudSyncActive = false))
            } else {
                handleSignedInUser(user)
            }
        }
        authListener = listener
        auth?.addAuthStateListener(listener)

        if (auth?.currentUser == null) signInAnonymously()
        else auth?.currentUser?.let(::handleSignedInUser)
    }

    private fun signInAnonymously() {
        val firebaseAuth = auth ?: return
        firebaseAuth.signInAnonymously().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                // Never show a login wall. The app simply stays in local guest mode.
                publish(AccountState(configured = true, cloudSyncActive = false))
            }
        }
    }

    private fun handleSignedInUser(user: FirebaseUser) {
        val fingerprint = "${user.uid}:${user.isAnonymous}:${user.email.orEmpty()}:${user.displayName.orEmpty()}"
        publish(
            AccountState(
                configured = true,
                uid = user.uid,
                anonymous = user.isAnonymous,
                displayName = user.displayName,
                email = user.email,
                provider = providerType(user),
                cloudSyncActive = database != null,
            ),
        )
        analytics?.setUserId(user.uid)
        analytics?.setUserProperty("account_type", providerType(user))
        updateProfile(user)
        // Public catalog now comes exclusively from the server/MongoDB.

        if (fingerprint != lastAccountFingerprint) {
            if (!user.isAnonymous) {
                restoreCloudState(user.uid) {
                    attachRealtimeStateListener(user.uid)
                    syncLocalState()
                }
            } else {
                attachRealtimeStateListener(user.uid)
                syncLocalState()
            }
        } else {
            syncLocalState()
        }
        lastAccountFingerprint = fingerprint
    }

    private fun updateProfile(user: FirebaseUser) {
        val db = database ?: return
        val values = hashMapOf<String, Any?>(
            "displayName" to user.displayName,
            "email" to user.email,
            "anonymous" to user.isAnonymous,
            "provider" to providerType(user),
            "lastSeenAt" to ServerValue.TIMESTAMP,
            "appVersion" to BuildConfig.VERSION_NAME,
        )
        db.getReference("users").child(user.uid).child("profile").updateChildren(values)
    }

    /**
     * Schedules a cloud write. Normal UI actions are de-bounced to one write after
     * 700 ms. onStop() can request an immediate queued write via [flushLocalState].
     */
    fun syncLocalState() = scheduleLocalStateSync(delayMs = 700L)

    fun flushLocalState() = scheduleLocalStateSync(delayMs = 0L)

    private fun scheduleLocalStateSync(delayMs: Long) {
        if (stopped) return
        val user = auth?.currentUser ?: return
        val db = database ?: return
        pendingSync?.cancel(false)
        pendingSync = syncExecutor.schedule(Runnable {
            if (stopped) return@Runnable
            val activeUser = auth?.currentUser ?: return@Runnable
            if (activeUser.uid != user.uid) return@Runnable

            val state = store.exportCloudState()
            val fingerprint = stateFingerprint(state)
            if (fingerprint == lastUploadedStateFingerprint) return@Runnable

            val values = hashMapOf<String, Any?>(
                "draftJson" to state.draftJson,
                "searchHistoryJson" to state.searchHistoryJson,
                "playbackHistoryJson" to state.playbackHistoryJson,
                "resultsCollapsed" to state.resultsCollapsed,
                "updatedAt" to ServerValue.TIMESTAMP,
            )
            db.getReference("users").child(activeUser.uid).child("state")
                .updateChildren(values)
                .addOnSuccessListener { lastUploadedStateFingerprint = fingerprint }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun restoreCloudState(uid: String, done: () -> Unit) {
        val db = database ?: return done()
        db.getReference("users").child(uid).child("state").get()
            .addOnSuccessListener { snapshot ->
                processRemoteSnapshot(snapshot, notifyUi = true, after = done)
            }
            .addOnFailureListener { done() }
    }

    private fun attachRealtimeStateListener(uid: String) {
        if (stateListenerUid == uid && stateListener != null) return
        detachRealtimeStateListener()
        val db = database ?: return
        val reference = db.getReference("users").child(uid).child("state")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                processRemoteSnapshot(snapshot, notifyUi = true)
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        stateReference = reference
        stateListener = listener
        stateListenerUid = uid
        reference.addValueEventListener(listener)
    }

    private fun processRemoteSnapshot(
        snapshot: DataSnapshot,
        notifyUi: Boolean,
        after: (() -> Unit)? = null,
    ) {
        if (stopped) {
            after?.let { activity.runOnUiThread(it) }
            return
        }
        val payload = CloudStatePayload(
            draftJson = snapshot.child("draftJson").getValue(String::class.java),
            searchHistoryJson = snapshot.child("searchHistoryJson").getValue(String::class.java),
            playbackHistoryJson = snapshot.child("playbackHistoryJson").getValue(String::class.java),
            resultsCollapsed = snapshot.child("resultsCollapsed").getValue(Boolean::class.java) ?: false,
        )
        syncExecutor.execute(Runnable {
            try {
                val remoteFingerprint = stateFingerprint(payload)
                val localBefore = store.exportCloudState()
                val localFingerprint = stateFingerprint(localBefore)

                // This is the echo of our own most recent write. Do not parse and
                // merge hundreds of JSON result objects again on the main thread.
                if (remoteFingerprint == localFingerprint || remoteFingerprint == lastUploadedStateFingerprint) {
                    return@Runnable
                }

                val changed = store.mergeCloudState(payload)
                if (changed) {
                    if (notifyUi) activity.runOnUiThread { restoredCallback?.invoke() }
                    // If another device contributed data, push the merged union back
                    // once. The fingerprint guard prevents an endless echo loop.
                    scheduleLocalStateSync(delayMs = 250L)
                }
            } finally {
                after?.let { activity.runOnUiThread(it) }
            }
        })
    }

    private fun detachRealtimeStateListener() {
        val listener = stateListener
        val reference = stateReference
        if (listener != null && reference != null) reference.removeEventListener(listener)
        stateReference = null
        stateListener = null
        stateListenerUid = null
    }

    private fun attachPublicCatalogListener() {
        if (catalogListener != null) return
        val reference = database?.getReference("publicCatalog")?.child("movies") ?: return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val movies = snapshot.children.mapNotNull { movie ->
                    val title = movie.child("title").getValue(String::class.java)?.trim().orEmpty()
                    val uri = sequenceOf("uri", "url", "sources/0/url", "qualities/0/url")
                        .mapNotNull { path -> movie.child(path).getValue(String::class.java)?.trim() }
                        .firstOrNull { it.isNotEmpty() }
                        .orEmpty()
                    if (title.isEmpty() || uri.isEmpty()) return@mapNotNull null
                    val order = movie.child("order").getValue(Long::class.java) ?: Long.MAX_VALUE
                    val categories = movie.child("categories").children.mapNotNull { child -> child.getValue(String::class.java)?.trim()?.takeIf(String::isNotEmpty) }
                    order to LibraryItem(
                        title = title,
                        uri = uri,
                        kind = movie.child("kind").getValue(String::class.java) ?: "hls",
                        source = movie.child("source").getValue(String::class.java)
                            ?: movie.child("sources/0/quality").getValue(String::class.java)
                            ?: movie.child("qualities/0/label").getValue(String::class.java)
                            ?: "Public catalog",
                        pageUrl = movie.child("pageUrl").getValue(String::class.java),
                        contentType = movie.child("contentType").getValue(String::class.java) ?: "full_movie",
                        downloadable = movie.child("downloadable").getValue(Boolean::class.java) ?: false,
                        downloadUrl = movie.child("downloadUrl").getValue(String::class.java),
                        categories = categories,
                    )
                }.sortedBy { it.first }.map { it.second }
                activity.runOnUiThread { catalogCallback?.invoke(movies) }
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        catalogReference = reference
        catalogListener = listener
        reference.addValueEventListener(listener)
    }

    private fun detachPublicCatalogListener() {
        val listener = catalogListener
        val reference = catalogReference
        if (listener != null && reference != null) reference.removeEventListener(listener)
        catalogReference = null
        catalogListener = null
    }

    fun signInWithGoogle(onResult: (Result<Unit>) -> Unit) {
        val firebaseAuth = auth
        if (firebaseAuth == null || !configured) {
            onResult(Result.failure(IllegalStateException("Firebase is not configured")))
            return
        }
        val clientId = webClientId()
        if (clientId.isNullOrBlank()) {
            onResult(Result.failure(IllegalStateException("Missing default_web_client_id")))
            return
        }

        activity.lifecycleScope.launch {
            try {
                // This is an explicit user action, so show all Google accounts that
                // can be used rather than requiring a previously authorized account.
                val googleOption = GetGoogleIdOption.Builder()
                    .setServerClientId(clientId)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build()
                val response = credentialManager.getCredential(activity, request)
                val credential = response.credential
                if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    onResult(Result.failure(IllegalStateException("Unexpected Google credential type")))
                    return@launch
                }
                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
                val current = firebaseAuth.currentUser
                if (current?.isAnonymous == true) {
                    current.linkWithCredential(firebaseCredential).addOnCompleteListener { linkTask ->
                        if (linkTask.isSuccessful) {
                            analytics?.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                                putString(FirebaseAnalytics.Param.METHOD, "google_link")
                            })
                            onResult(Result.success(Unit))
                        } else if (linkTask.exception is FirebaseAuthUserCollisionException) {
                            // The Google account already exists. Sign into it; local
                            // history remains on-device and is merged with its cloud
                            // state by handleSignedInUser().
                            firebaseAuth.signInWithCredential(firebaseCredential).addOnCompleteListener { signInTask ->
                                if (signInTask.isSuccessful) {
                                    analytics?.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                                        putString(FirebaseAnalytics.Param.METHOD, "google")
                                    })
                                    onResult(Result.success(Unit))
                                } else {
                                    onResult(Result.failure(signInTask.exception ?: IllegalStateException("Google sign-in failed")))
                                }
                            }
                        } else {
                            onResult(Result.failure(linkTask.exception ?: IllegalStateException("Google link failed")))
                        }
                    }
                } else {
                    firebaseAuth.signInWithCredential(firebaseCredential).addOnCompleteListener { signInTask ->
                        if (signInTask.isSuccessful) {
                            analytics?.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                                putString(FirebaseAnalytics.Param.METHOD, "google")
                            })
                            onResult(Result.success(Unit))
                        } else {
                            onResult(Result.failure(signInTask.exception ?: IllegalStateException("Google sign-in failed")))
                        }
                    }
                }
            } catch (error: GetCredentialException) {
                onResult(Result.failure(error))
            } catch (error: Exception) {
                onResult(Result.failure(error))
            }
        }
    }

    /** Human-readable diagnostics for Google sign-in failures. */
    fun googleSignInErrorDetails(error: Throwable?): String {
        val raw = error?.localizedMessage?.takeIf { it.isNotBlank() }
            ?: error?.javaClass?.simpleName
            ?: "Unknown Google sign-in error"
        val sha1 = currentSigningSha1()
        return if (sha1.isNullOrBlank()) raw else "$raw\nSHA-1: $sha1"
    }

    fun signInWithEmail(email: String, password: String, onResult: (Result<Unit>) -> Unit) {
        val firebaseAuth = auth
        if (firebaseAuth == null || !configured) {
            onResult(Result.failure(IllegalStateException("Firebase is not configured")))
            return
        }
        firebaseAuth.signInWithEmailAndPassword(email.trim(), password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    analytics?.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
                        putString(FirebaseAnalytics.Param.METHOD, "password")
                    })
                    onResult(Result.success(Unit))
                } else {
                    onResult(Result.failure(task.exception ?: IllegalStateException("Email sign-in failed")))
                }
            }
    }

    fun createAccountWithEmail(email: String, password: String, onResult: (Result<Unit>) -> Unit) {
        val firebaseAuth = auth
        if (firebaseAuth == null || !configured) {
            onResult(Result.failure(IllegalStateException("Firebase is not configured")))
            return
        }
        val cleanEmail = email.trim()
        val current = firebaseAuth.currentUser
        if (current?.isAnonymous == true) {
            val credential = EmailAuthProvider.getCredential(cleanEmail, password)
            current.linkWithCredential(credential).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    analytics?.logEvent(FirebaseAnalytics.Event.SIGN_UP, Bundle().apply {
                        putString(FirebaseAnalytics.Param.METHOD, "password")
                    })
                    onResult(Result.success(Unit))
                } else if (task.exception is FirebaseAuthUserCollisionException) {
                    // Existing email account: sign in instead of trapping the guest.
                    firebaseAuth.signInWithEmailAndPassword(cleanEmail, password).addOnCompleteListener { signInTask ->
                        if (signInTask.isSuccessful) onResult(Result.success(Unit))
                        else onResult(Result.failure(signInTask.exception ?: IllegalStateException("Email sign-in failed")))
                    }
                } else {
                    onResult(Result.failure(task.exception ?: IllegalStateException("Email account creation failed")))
                }
            }
        } else {
            firebaseAuth.createUserWithEmailAndPassword(cleanEmail, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        analytics?.logEvent(FirebaseAnalytics.Event.SIGN_UP, Bundle().apply {
                            putString(FirebaseAnalytics.Param.METHOD, "password")
                        })
                        onResult(Result.success(Unit))
                    } else {
                        onResult(Result.failure(task.exception ?: IllegalStateException("Email account creation failed")))
                    }
                }
        }
    }

    fun sendPasswordReset(email: String, onResult: (Result<Unit>) -> Unit) {
        val firebaseAuth = auth
        if (firebaseAuth == null || !configured) {
            onResult(Result.failure(IllegalStateException("Firebase is not configured")))
            return
        }
        firebaseAuth.sendPasswordResetEmail(email.trim()).addOnCompleteListener { task ->
            if (task.isSuccessful) onResult(Result.success(Unit))
            else onResult(Result.failure(task.exception ?: IllegalStateException("Password reset failed")))
        }
    }

    fun continueAsGuest() {
        auth?.signOut()
        activity.lifecycleScope.launch {
            runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
            signInAnonymously()
        }
    }

    fun logSearch(query: String, results: Int, movieLanguage: String, subtitleLanguage: String) {
        analytics?.logEvent("movie_search", Bundle().apply {
            putLong("query_length", query.length.toLong())
            putLong("result_count", results.toLong())
            putString("movie_language", movieLanguage.take(20))
            putString("subtitle_language", subtitleLanguage.take(20))
        })
    }

    fun logSearchFailure(error: Throwable) {
        val apiError = error as? ApiException
        analytics?.logEvent("movie_search_failure", Bundle().apply {
            putString("error_code", (apiError?.code ?: error.javaClass.simpleName).take(40))
            apiError?.statusCode?.let { putLong("http_status", it.toLong()) }
            apiError?.endpoint?.let { putString("endpoint", it.take(80)) }
            putString("has_request_id", (!apiError?.requestId.isNullOrBlank()).toString())
        })
    }

    fun logPlayback(item: LibraryItem) {
        analytics?.logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, Bundle().apply {
            putString(FirebaseAnalytics.Param.CONTENT_TYPE, item.kind.take(32))
            putString(FirebaseAnalytics.Param.ITEM_ID, item.uri.hashCode().toString())
            putString("content_type", item.contentType.take(32))
            putLong("hls_variants", item.hlsVariantCount.toLong())
        })
    }

    fun logSave(itemTitle: String, mediaType: String) {
        analytics?.logEvent("save_media", Bundle().apply {
            putString("media_type", mediaType.take(20))
            putLong("title_length", itemTitle.length.toLong())
        })
    }

    fun logTheme(mode: String) {
        analytics?.logEvent("theme_changed", Bundle().apply { putString("mode", mode.take(20)) })
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
    }

    private fun providerType(user: FirebaseUser): String {
        if (user.isAnonymous) return "anonymous"
        return when {
            user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID } -> "google"
            user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID } -> "email"
            else -> "account"
        }
    }

    private fun webClientId(): String? {
        val id = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        return if (id == 0) null else runCatching { activity.getString(id) }.getOrNull()
    }

    private fun currentSigningSha1(): String? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = activity.packageManager.getPackageInfo(activity.packageName, flags)
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return@runCatching null
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString(":") { "%02X".format(Locale.US, it) }
    }.getOrNull()

    private fun stateFingerprint(state: CloudStatePayload): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String?) {
            digest.update((value ?: "").toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        add(state.draftJson)
        add(state.searchHistoryJson)
        add(state.playbackHistoryJson)
        add(state.resultsCollapsed.toString())
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    private fun publish(state: AccountState) {
        accountCallback?.invoke(state)
    }

    fun stop() {
        stopped = true
        pendingSync?.cancel(false)
        detachRealtimeStateListener()
        detachPublicCatalogListener()
        authListener?.let { listener -> auth?.removeAuthStateListener(listener) }
        authListener = null
        accountCallback = null
        restoredCallback = null
        catalogCallback = null
        // Let already queued writes finish instead of interrupting Firebase mid-save.
        syncExecutor.shutdown()
    }
}
