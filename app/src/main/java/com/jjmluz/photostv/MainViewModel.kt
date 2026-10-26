package com.jjmluz.photostv

import android.app.Application
import android.util.Log
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jjmluz.photostv.data.Album
import com.jjmluz.photostv.data.Feed
import com.jjmluz.photostv.data.MediaItem
import com.jjmluz.photostv.data.MonthIndex
import com.jjmluz.photostv.data.NotSignedInException
import com.jjmluz.photostv.data.Person
import com.jjmluz.photostv.data.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

private const val TAG = "PhotosTV"

enum class Tab { Photos, Albums, People }

sealed interface AuthState {
    data object Checking : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val account: String?) : AuthState
    data class Failed(val error: Throwable) : AuthState
}

/** A grid of media with its own scroll position, so it survives navigating away and back. */
class MediaGrid(val feed: Feed<MediaItem>) {
    val gridState = LazyGridState()
    /** Item to move focus to the next time the grid is shown. */
    var restoreKey by mutableStateOf<String?>(null)
    /** Item that has focus right now, if any. */
    var focusedKey: String? = null
}

class AlbumPage(val album: Album, val grid: MediaGrid) {
    var focusedOnce = false
}

class PersonPage(val person: Person, val grid: MediaGrid) {
    var focusedOnce = false
}

class Viewer(val grid: MediaGrid, val startIndex: Int)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PhotosTvApp
    private val api = app.api

    var auth by mutableStateOf<AuthState>(AuthState.Checking)
        private set

    /** The signed-in account, for the avatar; just the email until its name and picture arrive. */
    var profile by mutableStateOf<Profile?>(null)
        private set

    /** The other accounts signed in on this TV, which the account menu can switch to. */
    var otherAccounts by mutableStateOf<List<Profile>>(emptyList())
        private set

    /** Whether the sign-in screen is showing to add an account, over the current one. */
    var addingAccount by mutableStateOf(false)
        private set

    /** Whether the account button takes focus when the library shows again, after adding was cancelled. */
    var refocusAccount = false

    private var connecting: Job? = null

    var tab by mutableStateOf(Tab.Photos)
        private set

    /** Whether the timeline has already pulled focus away from the drawer at startup. */
    var initialFocusDone = false

    val photos = MediaGrid(
        Feed(viewModelScope, MediaItem::key, api::timeline, seek = { api.timelineAt(it) }, timeOf = MediaItem::timestamp),
    )

    /** Months with photos, for the timeline panel on the Fotos page. */
    val months = MonthIndex(application, api, viewModelScope)

    val albums = Feed(viewModelScope, Album::key, api::albums)
    val albumsGridState = LazyGridState()
    var albumsRestoreKey by mutableStateOf<String?>(null)
    var albumsFocusedKey: String? = null

    var openAlbum by mutableStateOf<AlbumPage?>(null)
        private set

    val people = Feed(viewModelScope, Person::key, fetch = api::people)
    val peopleGridState = LazyGridState()
    var peopleRestoreKey by mutableStateOf<String?>(null)
    var peopleFocusedKey: String? = null

    var openPerson by mutableStateOf<PersonPage?>(null)
        private set

    var viewer by mutableStateOf<Viewer?>(null)
        private set

    private val locations = mutableMapOf<String, String?>()

    init {
        connect()
        // Signing in from the phone opens that account's library without any action on the TV. The
        // TV's web session is already the new account's, whatever was showing before.
        viewModelScope.launch {
            app.pairing.signedIn.collect {
                addingAccount = false
                resetLibrary()
                api.reset()
                connect()
            }
        }
    }

    fun connect() {
        auth = AuthState.Checking
        connecting?.cancel()
        connecting = viewModelScope.launch {
            auth = try {
                AuthState.SignedIn(api.connect()).also {
                    loadProfile(it.account)
                    photos.feed.refresh()
                    albums.refresh()
                    people.refresh()
                    months.refresh(app.account.email ?: it.account)
                }
            } catch (e: NotSignedInException) {
                Log.i(TAG, "connect: not signed in")
                // A cookies.txt session ended, but accounts signed in from the phone are still here.
                val saved = app.account.emails.firstOrNull()
                if (app.account.email == null && saved != null) {
                    switchAccount(saved)
                    return@launch
                }
                AuthState.SignedOut
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                AuthState.Failed(e)
            }
        }
    }

    private fun loadProfile(email: String?) {
        val account = app.account
        profile = account.savedProfile() ?: Profile(null, email, null)
        otherAccounts = account.emails.filter { it != account.email }.mapNotNull(account::savedProfile)
        viewModelScope.launch {
            runCatching { account.profile() }
                .onSuccess { if (it != null) profile = it }
                .onFailure { Log.w(TAG, "Profile failed", it) }
        }
    }

    /** Signs the current account out; the next saved account, if any, takes its place. */
    fun signOut() {
        viewModelScope.launch {
            months.clear()
            app.signOut()
            resetLibrary()
            val next = app.account.emails.firstOrNull()
            if (next != null) switchAccount(next) else auth = AuthState.SignedOut
        }
    }

    /** Shows the library of [email], one of [otherAccounts], from a web session of its own. */
    fun switchAccount(email: String) {
        auth = AuthState.Checking
        resetLibrary()
        connecting?.cancel()
        connecting = viewModelScope.launch {
            try {
                app.account.switchTo(email)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "switch to $email failed", e)
                auth = AuthState.Failed(e)
                return@launch
            }
            api.reset()
            connect()
        }
    }

    /** Shows the sign-in screen, so the phone can add another account; Back returns to this one. */
    fun addAccount() {
        addingAccount = true
    }

    fun cancelAddAccount() {
        refocusAccount = true
        addingAccount = false
    }

    /** A cookies.txt was imported: its session belongs to none of the saved accounts. */
    fun signedInWithCookies() {
        app.account.useCookieSession()
        addingAccount = false
        resetLibrary()
        api.reset()
        connect()
    }

    /** Forgets what was on screen for the previous account. */
    private fun resetLibrary() {
        profile = null
        otherAccounts = emptyList()
        openAlbum = null
        openPerson = null
        viewer = null
        tab = Tab.Photos
        initialFocusDone = false
        locations.clear()
        photos.restoreKey = null
        photos.focusedKey = null
        albumsRestoreKey = null
        albumsFocusedKey = null
        peopleRestoreKey = null
        peopleFocusedKey = null
    }

    /** Reloads the library from the top, so photos added since it loaded show up. */
    fun refresh() {
        if (auth !is AuthState.SignedIn) return
        photos.restoreKey = null
        photos.feed.refresh()
        albums.refresh()
        people.refresh()
        openAlbum?.grid?.feed?.refresh()
        openPerson?.grid?.feed?.refresh()
        months.refresh(app.account.email ?: (auth as AuthState.SignedIn).account)
    }

    fun select(target: Tab) {
        // Re-selecting Albums while inside an album goes back to the album list.
        if (target == Tab.Albums && tab == Tab.Albums) openAlbum = null
        if (target == Tab.People && tab == Tab.People) openPerson = null
        tab = target
    }

    /** Moves the Fotos grid to the newest photo of [month], loading from there if needed. */
    fun jumpTo(month: YearMonth) {
        val state = photos.feed.state.value
        fun inMonth(item: MediaItem) = YearMonth.from(item.localDateTime) == month
        val loadedFirst = state.items.firstOrNull(::inMonth)
        // The window is contiguous, so the month's first loaded item is its newest one when the
        // window starts above that month.
        val startsAbove = state.atTop || state.items.firstOrNull()?.let { YearMonth.from(it.localDateTime) > month } == true
        if (loadedFirst != null && startsAbove) {
            photos.restoreKey = loadedFirst.key
            return
        }
        val zoneOffsetMs = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds * 1000L
        val monthEnd = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - zoneOffsetMs - 1
        photos.feed.jumpTo(monthEnd) { items ->
            photos.restoreKey = (items.firstOrNull(::inMonth) ?: items.firstOrNull())?.key
        }
    }

    fun openAlbum(album: Album) {
        albumsRestoreKey = album.key
        openAlbum = AlbumPage(album, MediaGrid(Feed(viewModelScope, MediaItem::key, fetch = { api.albumItems(album, it) })))
            .also { it.grid.feed.loadMore() }
    }

    fun openPerson(person: Person) {
        peopleRestoreKey = person.key
        openPerson = PersonPage(person, MediaGrid(Feed(viewModelScope, MediaItem::key, fetch = { api.personItems(person, it) })))
            .also { it.grid.feed.loadMore() }
    }

    fun openViewer(grid: MediaGrid, index: Int) {
        viewer = Viewer(grid, index)
    }

    /** Where [item] was taken, if Google Photos knows; looked up once per item. */
    suspend fun locationOf(item: MediaItem): String? {
        if (item.key in locations) return locations[item.key]
        return try {
            api.location(item.key).also { locations[item.key] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "location lookup failed", e)
            null
        }
    }

    fun closeViewer(lastKey: String?) {
        viewer?.grid?.restoreKey = lastKey
        viewer = null
    }

    /**
     * Leaves an open album or person, else moves focus to the first item of the list on screen.
     * Returns false when that item already has focus, i.e. there is nothing left to go back to.
     */
    fun back(): Boolean {
        when {
            openAlbum != null && tab == Tab.Albums -> openAlbum = null
            openPerson != null && tab == Tab.People -> openPerson = null
            else -> return toFirstItem()
        }
        return true
    }

    private fun toFirstItem(): Boolean {
        when (tab) {
            Tab.Photos -> {
                val state = photos.feed.state.value
                val first = state.items.firstOrNull()?.key ?: return false
                if (state.atTop) {
                    if (photos.focusedKey == first) return false
                    photos.restoreKey = first
                } else {
                    // After a date jump the newest photos aren't loaded: start over from the top.
                    photos.feed.jumpTo(System.currentTimeMillis() + 2 * 24 * 60 * 60 * 1000L) { items ->
                        photos.restoreKey = items.firstOrNull()?.key
                    }
                }
            }
            Tab.Albums -> {
                val first = albums.state.value.items.firstOrNull()?.key ?: return false
                if (albumsFocusedKey == first) return false
                albumsRestoreKey = first
            }
            Tab.People -> {
                val first = people.state.value.items.firstOrNull()?.key ?: return false
                if (peopleFocusedKey == first) return false
                peopleRestoreKey = first
            }
        }
        return true
    }
}
