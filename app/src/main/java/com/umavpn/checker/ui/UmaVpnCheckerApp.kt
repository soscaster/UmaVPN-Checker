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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextAlign
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun UmaVpnCheckerApp(
    viewModel: UmaVpnViewModel = viewModel(),
    bottomInsetPadding: PaddingValues = PaddingValues(0.dp),
    onConnectInApp: ((String, OpenVpnVariant) -> Unit)? = null,
    favouriteIps: Set<String> = emptySet(),
    favouriteSummaries: Map<String, ServerSummary> = emptyMap(),
    onToggleFavourite: (String) -> Unit = {},
    onUpdateFavouriteSummary: (ServerSummary) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // After each fetch, update cached summaries for any favourite IPs found in results.
    LaunchedEffect(uiState.servers) {
        uiState.servers
            .filter { it.ip in favouriteIps }
            .forEach { onUpdateFavouriteSummary(it) }
    }

    // Merge: cached favourite summaries (for IPs not in fresh results) + fresh results,
    // sorted so favourites always appear first.
    val displayedServers = remember(uiState.servers, favouriteIps, favouriteSummaries) {
        if (favouriteIps.isEmpty()) {
            uiState.servers
        } else {
            val freshIps = uiState.servers.map { it.ip }.toSet()
            val missingFavs = favouriteIps
                .filter { it !in freshIps }
                .mapNotNull { favouriteSummaries[it] }
            (missingFavs + uiState.servers).sortedByDescending { it.ip in favouriteIps }
        }
    }
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
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets(0)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottomInsetPadding)
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
                    onRefresh = viewModel::refreshCurrentFilters
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

                displayedServers.isEmpty() -> {
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
                    items(displayedServers, key = { it.ip }) { server ->
                        ServerAccordionCard(
                            server = server,
                            isFavourite = server.ip in favouriteIps,
                            onToggleFavourite = { onToggleFavourite(server.ip) },
                            detailState = uiState.details[server.ip] ?: ServerDetailUiState(),
                            expanded = uiState.expandedIp == server.ip,
                            onToggleExpand = { viewModel.toggleAccordion(server.ip) },
                            onRetryDetail = { viewModel.retryDetail(server.ip) },
                            onOpenAsn = { asnId -> openExternalUrl(context, "https://ipinfo.io/$asnId") },
                            onConnect = {
                                if (onConnectInApp != null) {
                                    onConnectInApp(server.ip, OpenVpnVariant.CURRENT)
                                } else {
                                    val uri = viewModel.buildOpenVpnUri(server.ip, OpenVpnVariant.CURRENT)
                                    openOpenVpnImport(context, uri)
                                }
                            },
                            onAddToOpenVpnClient = { variant ->
                                if (onConnectInApp != null) {
                                    val uri = viewModel.buildOpenVpnUri(server.ip, variant)
                                    openOpenVpnImport(context, uri)
                                } else {
                                    val uri = viewModel.buildOpenVpnUri(server.ip, variant)
                                    openOpenVpnImport(context, uri)
                                }
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
    onRefresh: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Server Filters",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                    Text(if (expanded) "Collapse" else "Expand")
                }
            }

            AnimatedVisibility(visible = expanded) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
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
                            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Text("Refresh")
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
                                TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Text("Refresh")
                                }
                            }
                        }
                    }
                }
            }

            if (!expanded) {
                Text(
                    text = "Filters hidden. Tap Expand to change country, sites, and sort.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    var sliderValue by remember(uiState.resultCount) { mutableFloatStateOf(uiState.resultCount.toFloat()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Filters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        DropdownField(
            label = "Country",
            value = uiState.country.label,
            options = uiState.countries,
            optionLabel = { it.label },
            onSelected = onCountrySelected
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Result count: ${sliderValue.roundToInt()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it.roundToInt().toFloat() },
                valueRange = 1f..10f,
                steps = 8,
                onValueChangeFinished = {
                    val selectedCount = sliderValue.roundToInt().coerceIn(1, 10)
                    if (selectedCount != uiState.resultCount) {
                        onResultCountSelected(selectedCount)
                    }
                }
            )
        }

        DropdownField(
            label = "Order by",
            value = uiState.orderBy.label,
            options = OrderByOption.entries,
            optionLabel = { it.label },
            onSelected = onOrderBySelected
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequiredSitesColumn(
    selectedSites: Set<RequiredSite>,
    onSiteToggled: (RequiredSite) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Required sites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RequiredSite.entries.forEach { site ->
                FilterChip(
                    selected = site in selectedSites,
                    onClick = { onSiteToggled(site) },
                    label = { Text(site.label) }
                )
            }
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

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
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
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    detailState: ServerDetailUiState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRetryDetail: () -> Unit,
    onOpenAsn: (String) -> Unit,
    onConnect: () -> Unit,
    onAddToOpenVpnClient: (OpenVpnVariant) -> Unit
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server.country,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = server.ip,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = formatRelativeTimeFromRaw(server.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DataBadge(
                        text = "Speed ${formatSpeed(server.speed)}",
                        type = DataBadgeType.SPEED,
                        compact = true
                    )
                    DataBadge(
                        text = "Ping ${server.duration} ms",
                        type = DataBadgeType.PING,
                        compact = true
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 10.dp))

                    Text(
                        text = "Updated: ${formatAbsoluteTimestamp(server.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

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
                            SurfaceBadge(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                                ) {
                                    Icon(
                                        imageVector = if (isFavourite) Icons.Filled.Star else Icons.Default.StarBorder,
                                        contentDescription = null,
                                        tint = if (isFavourite) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable(onClick = onToggleFavourite)
                                    )
                                    val asnText = buildAnnotatedString {
                                        pushStringAnnotation(tag = "ASN", annotation = "https://ipinfo.io/${detailState.detail.asnId}")
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
                                    Text(
                                        text = asnText,
                                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSecondaryContainer),
                                        modifier = Modifier.clickable { onOpenAsn(detailState.detail.asnId) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            ConnectionActionButtons(
                                onConnect = onConnect,
                                onAddToOpenVpnClient = onAddToOpenVpnClient
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionActionButtons(
    onConnect: () -> Unit,
    onAddToOpenVpnClient: (OpenVpnVariant) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onConnect,
            modifier = Modifier.weight(1f).fillMaxHeight()
        ) {
            Text("Connect", textAlign = TextAlign.Center)
        }

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Button(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxSize()
            ) {
                Text("Add to OpenVPN Client", textAlign = TextAlign.Center)
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
                            onAddToOpenVpnClient(variant)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DataBadge(
    text: String,
    type: DataBadgeType,
    compact: Boolean = false
) {
    val background = when (type) {
        DataBadgeType.SPEED -> Color(0xFFD9EEFF)
        DataBadgeType.PING -> Color(0xFFDDF5E3)
    }
    val foreground = when (type) {
        DataBadgeType.SPEED -> Color(0xFF0B4E83)
        DataBadgeType.PING -> Color(0xFF1D6B2F)
    }

    SurfaceBadge(
        backgroundColor = background,
        contentColor = foreground,
        horizontalPadding = if (compact) 10.dp else 12.dp,
        verticalPadding = if (compact) 6.dp else 8.dp
    ) {
        Text(
            text = text,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = foreground
        )
    }
}

@Composable
private fun SurfaceBadge(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    horizontalPadding: androidx.compose.ui.unit.Dp = 12.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(999.dp)
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.ProvideTextStyle(
            value = MaterialTheme.typography.bodyMedium.copy(color = contentColor)
        ) {
            content()
        }
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

private fun formatAbsoluteTimestamp(raw: String): String {
    return runCatching {
        val instant = Instant.parse(raw)
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }.getOrDefault(raw)
}

private fun formatRelativeTimeFromRaw(raw: String): String {
    return runCatching {
        val instant = Instant.parse(raw)
        formatRelativeDuration(instant, Instant.now())
    }.getOrDefault(raw)
}

private fun formatRelativeDuration(from: Instant, to: Instant): String {
    val seconds = Duration.between(from, to).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds seconds ago"
        seconds < 3600 -> "${seconds / 60} minutes ago"
        seconds < 86400 -> "${seconds / 3600} hours ago"
        else -> "${seconds / 86400} days ago"
    }
}

private fun formatSpeed(speed: Double): String {
    return String.format(Locale.US, "%.1f", speed)
}

private enum class DataBadgeType {
    SPEED,
    PING
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
