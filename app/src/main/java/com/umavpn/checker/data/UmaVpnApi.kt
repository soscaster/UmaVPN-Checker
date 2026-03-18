package com.umavpn.checker.data

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.ResponseBody

interface UmaVpnApiService {
    @GET("api/server")
    suspend fun getServers(
        @Query("sites") sites: List<String>,
        @Query("take") take: Int,
        @Query("orderBy") orderBy: String,
        @Query("country") country: String
    ): ApiServerListResponse

    @GET("api/server/{ip}")
    suspend fun getServerDetail(
        @Path("ip") ip: String
    ): ApiServerDetailResponse

    @GET("api/server/{ip}/config")
    suspend fun getServerConfig(
        @Path("ip") ip: String,
        @Query("variant") variant: String
    ): ResponseBody
}

data class ApiServerListResponse(
    val success: Boolean,
    val data: List<ApiServerSummaryDto>
)

data class ApiServerSummaryDto(
    val ip: String,
    val country: String,
    val timestamp: String,
    val duration: Int,
    val speed: Double
)

data class ApiServerDetailResponse(
    val success: Boolean,
    val data: ApiServerDetailDto
)

data class ApiServerDetailDto(
    val speed: Double,
    val country: String,
    val lat: Double,
    val lon: Double,
    val asn: ApiAsnDto
)

data class ApiAsnDto(
    val id: String,
    val name: String
)
