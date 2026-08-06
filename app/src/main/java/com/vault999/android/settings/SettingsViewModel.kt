package com.vault999.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vault999.android.preferences.Appearance
import com.vault999.android.preferences.NetworkPolicy
import com.vault999.android.preferences.VaultPreferences
import com.vault999.android.preferences.VaultSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val preferences: VaultPreferences) : ViewModel() {
    val state: StateFlow<VaultSettings> = preferences.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        VaultSettings(),
    )

    fun setTree(uri: String?) = update { it.copy(safTreeUri = uri) }
    fun setNetwork(value: NetworkPolicy) = update { it.copy(networkPolicy = value) }
    fun setConcurrency(value: Int) = update { it.copy(downloadConcurrency = value) }
    fun setAppearance(value: Appearance) = update { it.copy(appearance = value) }
    fun setReducedMotion(value: Boolean) = update { it.copy(reducedMotion = value) }
    fun setEqualizer(value: Boolean) = update { it.copy(equalizerEnabled = value) }

    private fun update(block: (VaultSettings) -> VaultSettings) {
        viewModelScope.launch { preferences.update(block) }
    }

    companion object {
        fun factory(preferences: VaultPreferences): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(preferences) as T
        }
    }
}
