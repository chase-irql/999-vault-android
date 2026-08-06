package com.vault999.android

import android.content.Context
import com.vault999.android.catalog.CatalogRepository
import com.vault999.android.archive.ArchiveRepository
import com.vault999.android.database.VaultDatabase
import com.vault999.android.network.JuiceWrldApiClient
import com.vault999.android.search.SearchRepository
import com.vault999.android.viewer.ArchiveViewerRepository
import com.vault999.android.music.LibraryRepository
import com.vault999.android.listen.RadioRepository
import com.vault999.android.downloads.DownloadRepository
import com.vault999.android.downloads.VaultTransferScheduler
import com.vault999.android.preferences.VaultPreferences
import com.vault999.android.account.AccountRepository
import com.vault999.android.account.ListeningSyncRepository
import com.vault999.android.auth.AccountCloudHttpTransport
import com.vault999.android.auth.CloudLibraryRepository
import com.vault999.android.auth.RoomCloudLibraryStore
import com.vault999.android.network.ExactOrigin
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class VaultGraph(context: Context) {
    private val applicationContext = context.applicationContext
    val database: VaultDatabase by lazy { VaultDatabase.create(applicationContext) }
    val preferences: VaultPreferences by lazy { VaultPreferences(applicationContext) }
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
    val archiveApi: JuiceWrldApiClient by lazy { JuiceWrldApiClient(http) }
    val catalogRepository: CatalogRepository by lazy { CatalogRepository(database.songs(), archiveApi) }
    val archiveRepository: ArchiveRepository by lazy { ArchiveRepository(database.archive(), archiveApi) }
    val searchRepository: SearchRepository by lazy { SearchRepository(archiveApi) }
    val archiveViewerRepository: ArchiveViewerRepository by lazy { ArchiveViewerRepository(archiveApi, http) }
    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(database.library(), database.downloads(), database.sync())
    }
    val radioRepository: RadioRepository by lazy { RadioRepository(archiveApi, database.archive()) }
    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(applicationContext, database.downloads(), http, VaultTransferScheduler(applicationContext), preferences, archiveApi)
    }
    val accountRepository: AccountRepository by lazy {
        AccountRepository(applicationContext, http, BuildConfig.ACCOUNT_API_ORIGIN)
    }
    val accountCloudTransport: AccountCloudHttpTransport? by lazy {
        val origin = BuildConfig.ACCOUNT_API_ORIGIN.takeIf(String::isNotBlank)
        origin?.let { AccountCloudHttpTransport(http, ExactOrigin(it.toHttpUrl())) }
    }
    val cloudLibraryRepository: CloudLibraryRepository? by lazy {
        val sessions = accountRepository.authSessionManager
        val transport = accountCloudTransport
        if (sessions == null || transport == null) null else {
            CloudLibraryRepository(
                RoomCloudLibraryStore(database.cloudLibrary(), database.library()),
                sessions,
                transport,
                System::currentTimeMillis,
            )
        }
    }
    val listeningSyncRepository: ListeningSyncRepository? by lazy {
        val sessions = accountRepository.authSessionManager
        val transport = accountCloudTransport
        if (sessions == null || transport == null) null else ListeningSyncRepository(database.sync(), sessions, transport)
    }
}
