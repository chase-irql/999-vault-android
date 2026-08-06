package com.vault999.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.vault999.android.downloads.SafVaultStorage
import com.vault999.android.downloads.StoragePermissionLostException
import com.vault999.android.downloads.VaultPath
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Debug-only physical-device probe for the SAF revocation/regrant release gate. */
class StoragePermissionProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { exercise(intent.action) }
                .onFailure { Log.e(TAG, "SAF_PROBE action=${intent.action} result=failure type=${it.javaClass.simpleName}") }
            finish()
        }
    }

    private fun exercise(action: String?) {
        when (action) {
            ACTION_PREPARE -> {
                val uri = requireNotNull(contentResolver.persistedUriPermissions.firstOrNull()?.uri)
                preferences().edit().putString(KEY_URI, uri.toString()).apply()
                SafVaultStorage(contentResolver, uri).openSink(PRIOR).use { it.write(PRIOR_BYTES) }
                Log.d(TAG, "SAF_PROBE action=prepare result=success")
            }
            ACTION_RELEASE -> {
                val uri = Uri.parse(requireNotNull(preferences().getString(KEY_URI, null)))
                contentResolver.persistedUriPermissions.forEach { permission ->
                    var flags = 0
                    if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.releasePersistableUriPermission(permission.uri, flags)
                }
                val failure = runCatching {
                    SafVaultStorage(contentResolver, uri).openSink(REPLACEMENT).use { it.write(REPLACEMENT_BYTES) }
                }.exceptionOrNull()
                check(failure is StoragePermissionLostException) { "Expected StoragePermissionLostException" }
                Log.d(TAG, "SAF_PROBE action=release result=permission-loss-observed")
            }
            ACTION_VERIFY_REGRANT -> {
                val uri = requireNotNull(contentResolver.persistedUriPermissions.firstOrNull()?.uri)
                val storage = SafVaultStorage(contentResolver, uri)
                check(storage.openSource(PRIOR).use { it.readBytes() }.contentEquals(PRIOR_BYTES))
                storage.openSink(REPLACEMENT).use { it.write(REPLACEMENT_BYTES) }
                storage.move(REPLACEMENT, PRIOR, replaceExisting = true)
                check(storage.openSource(PRIOR).use { it.readBytes() }.contentEquals(REPLACEMENT_BYTES))
                Log.d(TAG, "SAF_PROBE action=verify-regrant result=prior-preserved-and-replacement-published")
            }
            else -> error("Unsupported SAF probe action")
        }
    }

    private fun preferences() = getSharedPreferences("storage-permission-probe", MODE_PRIVATE)

    private companion object {
        const val TAG = "Vault999"
        const val ACTION_PREPARE = "com.vault999.android.debug.SAF_PREPARE"
        const val ACTION_RELEASE = "com.vault999.android.debug.SAF_RELEASE"
        const val ACTION_VERIFY_REGRANT = "com.vault999.android.debug.SAF_VERIFY_REGRANT"
        const val KEY_URI = "uri"
        val PRIOR = VaultPath.of("qa/prior.txt")
        val REPLACEMENT = VaultPath.of("qa/replacement.part")
        val PRIOR_BYTES = "prior".toByteArray(StandardCharsets.UTF_8)
        val REPLACEMENT_BYTES = "replacement".toByteArray(StandardCharsets.UTF_8)
    }
}
