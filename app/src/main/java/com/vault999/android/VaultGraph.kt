package com.vault999.android

import android.content.Context
import com.vault999.android.catalog.CatalogRepository
import com.vault999.android.archive.ArchiveRepository
import com.vault999.android.database.VaultDatabase
import com.vault999.android.network.JuiceWrldApiClient
import com.vault999.android.search.SearchRepository
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class VaultGraph(context: Context) {
    val database: VaultDatabase by lazy { VaultDatabase.create(context.applicationContext) }
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
}
