package com.vault999.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun VaultWordmark(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(VaultColors.Yellow), contentAlignment = Alignment.Center) {
            Text("9", color = VaultColors.Canvas, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("999 VAULT", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text("JUICE WRLD ARCHIVE", style = MaterialTheme.typography.labelLarge, color = VaultColors.Cyan)
        }
    }
}

@Composable
fun VaultTrackRow(
    number: Long?,
    title: String,
    metadata: String,
    status: String,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    favorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Play $title", onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = listOfNotNull(number?.let { "Song $it" }, title, metadata, status).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(number?.toString()?.padStart(3, '0') ?: "—", color = VaultColors.Cyan, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(metadata, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, maxLines = 1, color = VaultColors.Green, style = MaterialTheme.typography.labelLarge)
        }
        onFavorite?.let { toggle ->
            IconButton(onClick = toggle, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (favorite) "Remove $title from device favorites" else "Add $title to device favorites",
                    tint = if (favorite) VaultColors.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play $title", tint = VaultColors.Yellow)
        }
    }
}

@Composable
fun VaultState(
    icon: ImageVector,
    title: String,
    message: String,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) CircularProgressIndicator(color = VaultColors.Cyan) else Icon(icon, contentDescription = null, tint = VaultColors.Cyan, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
