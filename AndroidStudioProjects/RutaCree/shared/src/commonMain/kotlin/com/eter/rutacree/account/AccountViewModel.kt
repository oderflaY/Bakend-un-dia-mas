package com.eter.rutacree.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.rutacree.api.ApiException
import com.eter.rutacree.api.DriverAccount
import com.eter.rutacree.api.DriverInfo
import com.eter.rutacree.api.LoginResult
import com.eter.rutacree.api.RiderProfile
import com.eter.rutacree.api.RutaCreeApi
import com.eter.rutacree.platform.SecureStore
import com.eter.rutacree.settings.KeyValueStore
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.transit.SendState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Who is using the app. The role comes from the server at sign-in, never from a user choice. */
enum class SessionKind {
    /** Nobody signed in: the login screen is shown. */
    None,

    /** A rider using the app without an account. */
    Guest,
    Rider,
    Driver,
}

enum class AccountError {
    InvalidCredentials, Locked, RateLimited, EmailTaken, WeakPassword, InvalidEmail, MissingName, Network,
    SessionExpired, Unknown,
}

data class AccountUiState(
    /** False in simulation mode (no backend): sign-in is replaced by demo entries. */
    val available: Boolean = false,
    val kind: SessionKind = SessionKind.None,
    val email: String = "",
    /** Rider profile: from the server when signed in, stored on the phone for guests. */
    val profile: RiderProfile = RiderProfile(wheelchair = true),
    /** Set when kind == Driver. Kept in memory only (the token is in the secure store). */
    val driver: DriverAccount? = null,
    val busy: Boolean = false,
    val error: AccountError? = null,
    val save: SendState = SendState.Idle,
) {
    val isRiderSide: Boolean get() = kind == SessionKind.Guest || kind == SessionKind.Rider
}

private const val TOKEN_KEY = "session.token"
private const val ROLE_KEY = "session.role"
private const val DRIVER_KEY = "session.driver"
private const val PROFILE_KEY = "rider.profile"
private const val ROLE_RIDER = "rider"
private const val ROLE_DRIVER = "driver"
private const val ROLE_GUEST = "guest"
private const val ROLE_DEMO_DRIVER = "demo-driver"
private const val MIN_PASSWORD = 8

private val DEMO_DRIVER = DriverAccount(token = "", driver = DriverInfo(id = "DEMO", name = "Chofer de prueba"))

class AccountViewModel(
    config: AppConfig,
    private val secure: SecureStore,
    private val store: KeyValueStore,
) : ViewModel() {

    private val api = config.apiBaseUrl.takeIf { it.isNotBlank() }?.let(::RutaCreeApi)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    init {
        revalidate()
    }

    /** Rebuilds the last session from storage so the app opens straight on the right screens. */
    private fun restore(): AccountUiState {
        val base = AccountUiState(available = api != null, profile = cachedProfile())
        val token = secure.read(TOKEN_KEY)
        return when (store.get(ROLE_KEY)) {
            ROLE_RIDER -> if (token != null && api != null) {
                base.copy(kind = SessionKind.Rider, email = base.profile.email)
            } else base
            ROLE_DRIVER -> {
                val info = store.get(DRIVER_KEY)?.let { runCatching { json.decodeFromString<DriverInfo>(it) }.getOrNull() }
                if (token != null && info != null && api != null) {
                    base.copy(kind = SessionKind.Driver, email = info.email, driver = DriverAccount(token, info))
                } else base
            }
            ROLE_GUEST -> base.copy(kind = SessionKind.Guest)
            ROLE_DEMO_DRIVER -> if (api == null) base.copy(kind = SessionKind.Driver, driver = DEMO_DRIVER) else base
            else -> base
        }
    }

    /** Confirms a restored session with the server; a revoked or expired one goes back to login. */
    private fun revalidate() {
        val client = api ?: return
        val token = secure.read(TOKEN_KEY) ?: return
        val current = _state.value
        viewModelScope.launch {
            try {
                when (current.kind) {
                    SessionKind.Rider -> applyProfile(client.me(token))
                    SessionKind.Driver -> {
                        val info = client.driverMe(token)
                        store.put(DRIVER_KEY, json.encodeToString(info))
                        _state.update { it.copy(driver = DriverAccount(token, info), email = info.email) }
                    }
                    else -> Unit
                }
            } catch (e: ApiException) {
                // Offline keeps the stored session; only an explicit rejection signs out.
                if (e.unauthorized) sessionExpired()
            }
        }
    }

    fun login(email: String, password: String) {
        val client = api ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = client.login(email.trim(), password)) {
                    is LoginResult.Rider -> startRider(result.session.token, result.session.rider)
                    is LoginResult.Driver -> startDriver(result.account)
                }
            } catch (e: ApiException) {
                fail(e.toAccountError())
            }
        }
    }

    fun register(name: String, email: String, password: String) {
        val client = api ?: return
        when {
            name.isBlank() -> return fail(AccountError.MissingName)
            password.length < MIN_PASSWORD -> return fail(AccountError.WeakPassword)
        }
        // What a guest configured before creating the account travels with it.
        val local = _state.value.profile
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val session = client.register(email.trim(), password, name.trim())
                val merged = local.copy(id = session.rider.id, email = session.rider.email, name = session.rider.name)
                val profile = runCatching { client.updateProfile(session.token, merged) }.getOrDefault(session.rider)
                startRider(session.token, profile)
            } catch (e: ApiException) {
                fail(e.toAccountError())
            }
        }
    }

    private fun startRider(token: String, profile: RiderProfile) {
        secure.write(TOKEN_KEY, token)
        store.put(ROLE_KEY, ROLE_RIDER)
        cache(profile)
        _state.update {
            it.copy(kind = SessionKind.Rider, profile = profile, email = profile.email, driver = null, busy = false, error = null)
        }
    }

    private fun startDriver(account: DriverAccount) {
        secure.write(TOKEN_KEY, account.token)
        store.put(ROLE_KEY, ROLE_DRIVER)
        store.put(DRIVER_KEY, json.encodeToString(account.driver))
        // A driver's phone must not keep a rider profile from someone else.
        store.put(PROFILE_KEY, null)
        _state.update {
            AccountUiState(available = it.available, kind = SessionKind.Driver, email = account.driver.email, driver = account)
        }
    }

    fun continueAsGuest() {
        store.put(ROLE_KEY, ROLE_GUEST)
        _state.update { it.copy(kind = SessionKind.Guest, error = null, busy = false) }
    }

    /** Simulation mode only: explore the driver screens without a server. */
    fun continueAsDemoDriver() {
        if (api != null) return
        store.put(ROLE_KEY, ROLE_DEMO_DRIVER)
        _state.update { it.copy(kind = SessionKind.Driver, driver = DEMO_DRIVER, error = null) }
    }

    /** From a guest's profile: back to the login screen, keeping what they configured on this phone. */
    fun openLogin() {
        store.put(ROLE_KEY, null)
        _state.update { it.copy(kind = SessionKind.None, error = null) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun signOut() = clearSession(error = null)

    /** A server rejected the stored token (expired, deactivated): back to login with an explanation. */
    fun sessionExpired() = clearSession(error = AccountError.SessionExpired)

    private fun clearSession(error: AccountError?) {
        secure.write(TOKEN_KEY, null)
        store.put(ROLE_KEY, null)
        store.put(DRIVER_KEY, null)
        // Shared phones: nothing of the previous person stays behind.
        store.put(PROFILE_KEY, null)
        _state.update { AccountUiState(available = it.available, error = error) }
    }

    /** Deletes the rider account on the server, then everything on this phone. */
    fun deleteAccount() {
        val token = secure.read(TOKEN_KEY) ?: return
        val client = api ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                client.deleteAccount(token)
                signOut()
            } catch (e: ApiException) {
                if (e.unauthorized) signOut() else fail(e.toAccountError())
            }
        }
    }

    fun editProfile(transform: (RiderProfile) -> RiderProfile) =
        _state.update { it.copy(profile = transform(it.profile), save = SendState.Idle) }

    fun saveProfile() {
        val current = _state.value
        cache(current.profile)
        val token = secure.read(TOKEN_KEY)
        val client = api
        if (current.kind != SessionKind.Rider || token == null || client == null) {
            _state.update { it.copy(save = SendState.Sent) }
            return
        }
        _state.update { it.copy(save = SendState.Sending) }
        viewModelScope.launch {
            try {
                applyProfile(client.updateProfile(token, current.profile))
                _state.update { it.copy(save = SendState.Sent) }
            } catch (e: ApiException) {
                if (e.unauthorized) sessionExpired() else _state.update { it.copy(save = SendState.Failed) }
            }
        }
    }

    private fun applyProfile(profile: RiderProfile) {
        cache(profile)
        _state.update { it.copy(profile = profile, email = profile.email) }
    }

    private fun fail(error: AccountError) = _state.update { it.copy(busy = false, error = error) }

    private fun cache(profile: RiderProfile) = store.put(PROFILE_KEY, json.encodeToString(profile))

    private fun cachedProfile(): RiderProfile =
        store.get(PROFILE_KEY)?.let { runCatching { json.decodeFromString<RiderProfile>(it) }.getOrNull() }
            ?: RiderProfile(wheelchair = true)

    private fun ApiException.toAccountError() = when {
        unauthorized -> AccountError.InvalidCredentials
        code == "locked" -> AccountError.Locked
        locked -> AccountError.RateLimited
        conflict -> AccountError.EmailTaken
        code == "weak_password" -> AccountError.WeakPassword
        code == "invalid_email" -> AccountError.InvalidEmail
        status == null -> AccountError.Network
        else -> AccountError.Unknown
    }
}
