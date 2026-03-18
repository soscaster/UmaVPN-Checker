package com.umavpn.checker.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.umavpn.checker.ServerDetailUiState
import com.umavpn.checker.UmaVpnUiState
import com.umavpn.checker.UmaVpnViewModel
import com.umavpn.checker.data.CountryOption
import com.umavpn.checker.data.OpenVpnVariant
import com.umavpn.checker.data.OrderByOption
import com.umavpn.checker.data.RequiredSite
import com.umavpn.checker.data.ServerSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UmaVpnCheckerApp(
    viewModel: UmaVpnViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.siteSelectionError) {
        val error = uiState.siteSelectionError ?: return@LaunchedEffect
        scope.launch {
            snackbarHostState.showSnackbar(error)
            viewModel.clearSiteSelectionError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "UmaVPN Checker") })
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                FilterPanel(
                    uiState = uiState,
                    onCountrySelected = viewModel::onCountrySelected,
                    onResultCountSelected = viewModel::onResultCountSelected,
                    onOrderBySelected = viewModel::onOrderBySelected,
                    onSiteToggled = viewModel::onToggleSite,
                    onReset = viewModel::resetFilters
                )
            }

            when {
                uiState.isLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                uiState.listError != null -> {
                    val listError = uiState.listError ?: "Failed to load server list"
                    item {
                        ErrorPanel(
                            message = listError,
                            retryLabel = "Retry",
                            onRetry = viewModel::retryList
                        )
                    }
                }

                uiState.servers.isEmpty() -> {
                    item {
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "No servers matched current filters.",
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                else -> {
                    items(uiState.servers, key = { it.ip }) { server ->
                        ServerAccordionCard(
                            server = server,
                            detailState = uiState.details[server.ip] ?: ServerDetailUiState(),
                            expanded = uiState.expandedIp == server.ip,
                            onToggleExpand = { viewModel.toggleAccordion(server.ip) },
                            onRetryDetail = { viewModel.retryDetail(server.ip) },
                            onOpenAsn = { asnId -> openExternalUrl(context, "https://ipinfo.io/$asnId") },
                            onOpenOpenVpn = { variant ->
                                val uri = viewModel.buildOpenVpnUri(server.ip, variant)
                                openOpenVpnImport(context, uri)
                            }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun FilterPanel(
    uiState: UmaVpnUiState,
    onCountrySelected: (CountryOption) -> Unit,
    onResultCountSelected: (Int) -> Unit,
    onOrderBySelected: (OrderByOption) -> Unit,
    onSiteToggled: (RequiredSite) -> Unit,
    onReset: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            if (maxWidth < 680.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FiltersColumn(
                        uiState = uiState,
                        onCountrySelected = onCountrySelected,
                        onResultCountSelected = onResultCountSelected,
                        onOrderBySelected = onOrderBySelected
                    )
                    RequiredSitesColumn(
                        selectedSites = uiState.selectedSites,
                        onSiteToggled = onSiteToggled
                    )
                    TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.height(0.dp))
                        Text("Reset")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        FiltersColumn(
                            uiState = uiState,
                            onCountrySelected = onCountrySelected,
                            onResultCountSelected = onResultCountSelected,
                            onOrderBySelected = onOrderBySelected
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        RequiredSitesColumn(
                            selectedSites = uiState.selectedSites,
                            onSiteToggled = onSiteToggled
                        )
                        TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text("Reset")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersColumn(
    uiState: UmaVpnUiState,
    onCountrySelected: (CountryOption) -> Unit,
    onResultCountSelected: (Int) -> Unit,
    onOrderBySelected: (OrderByOption) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Filters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        DropdownField(
            label = "Country",
            value = uiState.country.label,
            options = uiState.countries,
            optionLabel = { it.label },
            onSelected = onCountrySelected
        )

        DropdownField(
            label = "Result count",
            value = uiState.resultCount.toString(),
            options = uiState.resultCountOptions,
            optionLabel = { it.toString() },
            onSelected = onResultCountSelected
        )

        DropdownField(
            label = "Order by",
            value = uiState.orderBy.label,
            options = OrderByOption.entries,
            optionLabel = { it.label },
            onSelected = onOrderBySelected
        )
    }
}

@Composable
private fun RequiredSitesColumn(
    selectedSites: Set<RequiredSite>,
    onSiteToggled: (RequiredSite) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Required sites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        RequiredSite.entries.forEach { site ->
            FilterChip(
                selected = site in selectedSites,
                onClick = { onSiteToggled(site) },
                label = { Text(site.label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownField(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Box {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null
                )
            }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ServerAccordionCard(
    server: ServerSummary,
    detailState: ServerDetailUiState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRetryDetail: () -> Unit,
    onOpenAsn: (String) -> Unit,
    onOpenOpenVpn: (OpenVpnVariant) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${server.country}  ${server.ip}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = formatTimestamp(server.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Divider(modifier = Modifier.padding(bottom = 10.dp))

                    when {
                        detailState.isLoading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        detailState.errorMessage != null -> {
                            ErrorPanel(
                                message = detailState.errorMessage,
                                retryLabel = "Retry detail",
                                onRetry = onRetryDetail
                            )
                        }

                        detailState.detail != null -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                DataBadge(text = "Speed ${formatSpeed(detailState.detail.speed)}")
                                DataBadge(text = "Ping ${server.duration} ms")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            SurfaceBadge {
                                val asnUrl = "https://ipinfo.io/${detailState.detail.asnId}"
                                val asnText = buildAnnotatedString {
                                    pushStringAnnotation(tag = "ASN", annotation = asnUrl)
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline,
                                            fontWeight = FontWeight.Bold
                                        )
                                    ) {
                                        append(detailState.detail.asnId)
                                    }
                                    pop()
                                    append(" ${detailState.detail.asnName}")
                                }
                                ClickableText(
                                    text = asnText,
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSecondaryContainer),
                                    onClick = { offset ->
                                        asnText.getStringAnnotations(tag = "ASN", start = offset, end = offset)
                                            .firstOrNull()
                                            ?.let { onOpenAsn(detailState.detail.asnId) }
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            AddToOpenVpnButton(onOpenOpenVpn = onOpenOpenVpn)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddToOpenVpnButton(
    onOpenOpenVpn: (OpenVpnVariant) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add to OpenVPN Client")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            OpenVpnVariant.entries.forEach { variant ->
                DropdownMenuItem(
                    text = { Text(variant.label) },
                    onClick = {
                        expanded = false
                        onOpenOpenVpn(variant)
                    }
                )
            }
        }
    }
}

@Composable
private fun DataBadge(text: String) {
    SurfaceBadge {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SurfaceBadge(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        content()
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = message,
                color = Color(0xFFB3261E),
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onRetry) {
                Text(retryLabel)
            }
        }
    }
}

private fun formatTimestamp(raw: String): String {
    return runCatching {
        val instant = Instant.parse(raw)
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault(raw)
}

private fun formatSpeed(speed: Double): String {
    return String.format(Locale.US, "%.1f", speed)
}

private fun openExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
        }
}

private fun openOpenVpnImport(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "OpenVPN client is not installed", Toast.LENGTH_SHORT).show()
    }
}
