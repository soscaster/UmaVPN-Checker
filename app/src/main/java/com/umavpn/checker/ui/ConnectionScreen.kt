package com.umavpn.checker.ui

import android.net.TrafficStats
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.umavpn.checker.vpn.VpnState
import kotlinx.coroutines.delay

@Composable
fun ConnectionScreen(
    state: VpnState,
    logs: List<String>,
    onDisconnect: () -> Unit,
    onClearLogs: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val uid = context.applicationInfo.uid

    var rxSpeed by remember { mutableLongStateOf(0L) }
    var txSpeed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state) {
        if (state !is VpnState.Connected) {
            rxSpeed = 0L; txSpeed = 0L
            return@LaunchedEffect
        }
        var prevRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
        var prevTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
        while (true) {
            delay(1000L)
            val curRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            val curTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            rxSpeed = curRx - prevRx
            txSpeed = curTx - prevTx
            prevRx = curRx
            prevTx = curTx
        }
    }

    var logsExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Status card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when (state) {
                        VpnState.Disconnected -> "Disconnected"
                        VpnState.Connecting   -> "Connecting..."
                        is VpnState.Connected -> "Connected"
                        is VpnState.Error     -> "Error: ${state.message}"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.3f
                    )
                )
                if (state is VpnState.Connected) {
                    Text(
                        text = parseServerEndpoint(state.endpoint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "↑ ${formatSpeed(txSpeed)}   ↓ ${formatSpeed(rxSpeed)}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * 1.2f
                        )
                    )
                }
                Button(
                    onClick = onDisconnect,
                    enabled = state !is VpnState.Disconnected
                ) {
                    Text("Disconnect")
                }
            }
        }

        // 3. Collapsible log section header
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { logsExpanded = !logsExpanded }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Connection Logs (${logs.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {
                            val allLogs = logs.joinToString(separator = "\n")
                            clipboardManager.setText(AnnotatedString(allLogs))
                            Toast.makeText(context, "Copied ${logs.size} log lines", Toast.LENGTH_SHORT).show()
                        },
                        label = { Text("Copy") }
                    )
                    AssistChip(
                        onClick = onClearLogs,
                        label = { Text("Clear") }
                    )
                    Icon(
                        imageVector = if (logsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }
        }

        // 4. Log frame — visible only when expanded
        AnimatedVisibility(visible = logsExpanded) {
            val listState = rememberLazyListState()
            OutlinedCard(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .verticalScrollbar(listState),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(logs) { index, line ->
                        Text(
                            text = "${index + 1}. $line",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

private fun parseServerEndpoint(endpoint: String): String {
    if (endpoint.startsWith("SUCCESS,")) {
        val p = endpoint.split(",")
        if (p.size >= 4) return "${p[2]}:${p[3]}"
    }
    return endpoint.substringBefore(' ')
}

private fun formatSpeed(b: Long): String = when {
    b < 1024L      -> "$b B/s"
    b < 1_048_576L -> "%.1f KB/s".format(b / 1024.0)
    else           -> "%.1f MB/s".format(b / 1_048_576.0)
}

