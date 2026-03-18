package com.umavpn.checker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umavpn.checker.data.CountryOption
import com.umavpn.checker.data.OpenVpnVariant
import com.umavpn.checker.data.OrderByOption
import com.umavpn.checker.data.RequiredSite
import com.umavpn.checker.data.ServerDetail
import com.umavpn.checker.data.ServerSummary
import com.umavpn.checker.data.UmaVpnRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val DEFAULT_COUNTRY = CountryOption("JP", "Japan")
private val DEFAULT_RESULT_COUNT = 5
private val DEFAULT_ORDER = OrderByOption.MOST_RECENT

private val COUNTRY_OPTIONS = listOf(
    DEFAULT_COUNTRY,
    CountryOption("US", "United States"),
    CountryOption("SG", "Singapore"),
    CountryOption("KR", "South Korea"),
    CountryOption("HK", "Hong Kong"),
    CountryOption("TW", "Taiwan")
)

private val RESULT_OPTIONS = listOf(5, 10, 15, 20)

class UmaVpnViewModel(
    private val repository: UmaVpnRepository = UmaVpnRepository.create()
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        UmaVpnUiState(
            country = DEFAULT_COUNTRY,
            countries = COUNTRY_OPTIONS,
            resultCount = DEFAULT_RESULT_COUNT,
            resultCountOptions = RESULT_OPTIONS,
            orderBy = DEFAULT_ORDER,
            selectedSites = RequiredSite.entries.filter { it.defaultEnabled }.toSet()
        )
    )
    val uiState: StateFlow<UmaVpnUiState> = _uiState.asStateFlow()

    private var listRequestJob: Job? = null

    init {
        refreshServers()
    }

    fun onCountrySelected(country: CountryOption) {
        _uiState.update { it.copy(country = country) }
        refreshServers()
    }

    fun onResultCountSelected(count: Int) {
        _uiState.update { it.copy(resultCount = count) }
        refreshServers()
    }

    fun onOrderBySelected(order: OrderByOption) {
        _uiState.update { it.copy(orderBy = order) }
        refreshServers()
    }

    fun onToggleSite(site: RequiredSite) {
        val current = _uiState.value.selectedSites
        val next = if (site in current) current - site else current + site
        if (next.isEmpty()) {
            _uiState.update { it.copy(siteSelectionError = "At least one required site must stay enabled") }
            return
        }
        _uiState.update {
            it.copy(
                selectedSites = next,
                siteSelectionError = null
            )
        }
        refreshServers()
    }

    fun clearSiteSelectionError() {
        _uiState.update { it.copy(siteSelectionError = null) }
    }

    fun resetFilters() {
        _uiState.update {
            it.copy(
                country = DEFAULT_COUNTRY,
                resultCount = DEFAULT_RESULT_COUNT,
                orderBy = DEFAULT_ORDER,
                selectedSites = RequiredSite.entries.filter { site -> site.defaultEnabled }.toSet(),
                expandedIp = null,
                details = emptyMap(),
                siteSelectionError = null
            )
        }
        refreshServers()
    }

    fun toggleAccordion(ip: String) {
        val currentExpanded = _uiState.value.expandedIp
        if (currentExpanded == ip) {
            _uiState.update { it.copy(expandedIp = null) }
            return
        }

        _uiState.update { it.copy(expandedIp = ip) }

        val detailState = _uiState.value.details[ip]
        if (detailState?.detail == null && detailState?.isLoading != true) {
            fetchDetail(ip)
        }
    }

    fun retryList() {
        refreshServers()
    }

    fun retryDetail(ip: String) {
        fetchDetail(ip)
    }

    fun buildOpenVpnUri(ip: String, variant: OpenVpnVariant): String {
        return "openvpn://import-profile/https://api.umavpn.top/api/server/$ip/config?variant=${variant.apiValue}"
    }

    private fun refreshServers() {
        listRequestJob?.cancel()
        val snapshot = _uiState.value
        listRequestJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    listError = null,
                    expandedIp = null,
                    details = emptyMap()
                )
            }
            repository.fetchServers(
                country = snapshot.country.code,
                resultCount = snapshot.resultCount,
                orderBy = snapshot.orderBy.apiValue,
                sites = snapshot.selectedSites.map { it.apiValue }
            ).onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        servers = result,
                        listError = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        servers = emptyList(),
                        listError = throwable.message ?: "Failed to load server list"
                    )
                }
            }
        }
    }

    private fun fetchDetail(ip: String) {
        _uiState.update {
            val updated = it.details.toMutableMap()
            updated[ip] = ServerDetailUiState(isLoading = true)
            it.copy(details = updated)
        }

        viewModelScope.launch {
            repository.fetchServerDetail(ip)
                .onSuccess { detail ->
                    _uiState.update {
                        val updated = it.details.toMutableMap()
                        updated[ip] = ServerDetailUiState(isLoading = false, detail = detail)
                        it.copy(details = updated)
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        val updated = it.details.toMutableMap()
                        updated[ip] = ServerDetailUiState(
                            isLoading = false,
                            detail = null,
                            errorMessage = throwable.message ?: "Failed to load server detail"
                        )
                        it.copy(details = updated)
                    }
                }
        }
    }
}

data class UmaVpnUiState(
    val country: CountryOption,
    val countries: List<CountryOption>,
    val resultCount: Int,
    val resultCountOptions: List<Int>,
    val orderBy: OrderByOption,
    val selectedSites: Set<RequiredSite>,
    val isLoading: Boolean = false,
    val listError: String? = null,
    val siteSelectionError: String? = null,
    val servers: List<ServerSummary> = emptyList(),
    val expandedIp: String? = null,
    val details: Map<String, ServerDetailUiState> = emptyMap()
)

data class ServerDetailUiState(
    val isLoading: Boolean = false,
    val detail: ServerDetail? = null,
    val errorMessage: String? = null
)
