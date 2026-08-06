package com.vault999.android.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AccountViewModel(private val repository: AccountRepository) : ViewModel() {
    val state: StateFlow<AccountUiState> = repository.state

    init { viewModelScope.launch { repository.restore() } }
    fun signIn() { viewModelScope.launch { repository.startSignIn() } }
    fun browserOpened() = repository.browserOpened()
    fun logout() { viewModelScope.launch { repository.logout() } }

    companion object {
        fun factory(repository: AccountRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountViewModel(repository) as T
        }
    }
}
