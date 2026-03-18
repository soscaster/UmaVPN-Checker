package com.umavpn.checker.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class UmaVpnRepository(
    private val api: UmaVpnApiService
) {
    suspend fun fetchServers(
        country: String,
        resultCount: Int,
        orderBy: String,
        sites: List<String>
    ): Result<List<ServerSummary>> {
        return runCatching {
            val response = api.getServers(
                sites = sites,
                take = resultCount,
                orderBy = orderBy,
                country = country
            )
            if (!response.success) {
                error("Server returned unsuccessful response")
            }
            response.data.map {
                ServerSummary(
                    ip = it.ip,
                    country = it.country,
                    timestamp = it.timestamp,
                    duration = it.duration,
                    speed = it.speed
                )
            }
        }
    }

    suspend fun fetchServerDetail(ip: String): Result<ServerDetail> {
        return runCatching {
            val response = api.getServerDetail(ip)
            if (!response.success) {
                error("Server detail lookup failed")
            }
            val detail = response.data
            ServerDetail(
                ip = ip,
                speed = detail.speed,
                country = detail.country,
                lat = detail.lat,
                lon = detail.lon,
                asnId = detail.asn.id,
                asnName = detail.asn.name
            )
        }
    }

    suspend fun fetchRawConfig(ip: String, variant: String): Result<String> {
        return runCatching {
            api.getServerConfig(ip = ip, variant = variant).string()
        }
    }

    companion object {
        fun create(): UmaVpnRepository {
            val logger = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(logger)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl("https://api.umavpn.top/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            return UmaVpnRepository(retrofit.create(UmaVpnApiService::class.java))
        }
    }
}

data class ServerSummary(
    val ip: String,
    val country: String,
    val timestamp: String,
    val duration: Int,
    val speed: Double
)

data class ServerDetail(
    val ip: String,
    val speed: Double,
    val country: String,
    val lat: Double,
    val lon: Double,
    val asnId: String,
    val asnName: String
)

enum class OrderByOption(
    val label: String,
    val apiValue: String
) {
    FASTEST_SPEED("Fastest speed", "speed"),
    LOWEST_PING("Lowest ping", "duration"),
    MOST_RECENT("Most recent", "timestamp")
}

enum class RequiredSite(
    val label: String,
    val apiValue: String,
    val defaultEnabled: Boolean
) {
    UMA_JP("Umamusume (Japanese)", "uma", true),
    DMM("DMM", "dmm", true),
    UMA_GLOBAL("Umamusume (Global)", "umag", false)
}

enum class OpenVpnVariant(
    val label: String,
    val apiValue: String
) {
    CURRENT("OPVN Current", "current"),
    BETA("OPVN Beta", "beta"),
    LEGACY("OPVN Legacy", "legacy")
}

data class CountryOption(
    val code: String,
    val name: String
) {
    val label: String
        get() = "$code $name"
}
