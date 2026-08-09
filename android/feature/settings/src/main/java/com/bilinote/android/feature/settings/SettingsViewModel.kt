package com.bilinote.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilinote.android.network.BiliNoteApi
import com.bilinote.android.network.BackendUrl
import com.bilinote.android.network.NetworkModule
import com.bilinote.android.network.RetrofitBiliNoteApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun interface SettingsConnectionTester {
    suspend fun test(url: String, token: String?)
}

data class SettingsUiState(
    val backendUrl: String = "",
    val accessToken: String = "",
    val connectionMessage: String? = null,
    val isTestingConnection: Boolean = false,
    val errorMessage: String? = null,
    val isLoaded: Boolean = false,
    val tokenError: String? = null,
)

class SettingsViewModel(
    private val repository: SettingsStore,
    private val connectionTester: SettingsConnectionTester = SettingsConnectionTester { url, token ->
        val retrofit = NetworkModule.createRetrofit(url, token)
        BiliNoteApi(retrofit.create(RetrofitBiliNoteApi::class.java)).sysHealth()
    },
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var connectionJob: Job? = null
    private var connectionRequestId = 0L

    init {
        viewModelScope.launch {
            repository.observeSettings.collect { settings ->
                _uiState.update {
                    it.copy(
                        backendUrl = settings.backendUrl,
                        accessToken = settings.accessToken.orEmpty(),
                        isLoaded = true,
                        tokenError = settings.tokenError,
                    )
                }
            }
        }
    }

    fun saveUrl(rawUrl: String) {
        save(rawUrl, uiState.value.accessToken)
    }

    fun saveToken(token: String) {
        save(uiState.value.backendUrl, token)
    }

    fun testConnection(backendUrl: String = uiState.value.backendUrl, accessToken: String = uiState.value.accessToken) {
        connectionJob?.cancel()
        val requestId = ++connectionRequestId
        val url = backendUrl.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(isTestingConnection = false, connectionMessage = null, errorMessage = "请输入服务地址") }
            return
        }
        connectionJob = viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true, connectionMessage = null, errorMessage = null) }
            try {
                connectionTester.test(url, accessToken.ifBlank { null })
                _uiState.update {
                    if (requestId == connectionRequestId) it.copy(isTestingConnection = false, connectionMessage = "连接成功") else it
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    if (requestId == connectionRequestId) it.copy(isTestingConnection = false, errorMessage = exception.message ?: "连接失败") else it
                }
            }
        }
    }

    fun save(backendUrl: String, accessToken: String) {
        val normalizedUrl = try {
            BackendUrl.normalize(backendUrl)
        } catch (exception: IllegalArgumentException) {
            _uiState.update { it.copy(errorMessage = exception.message ?: "服务地址无效") }
            return
        }
        viewModelScope.launch {
            try {
                repository.saveSettings(normalizedUrl, accessToken.trim().ifBlank { null })
                _uiState.update { it.copy(backendUrl = normalizedUrl, errorMessage = null, tokenError = null) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update { it.copy(errorMessage = exception.message ?: "保存失败") }
            }
        }
    }
}
