package com.laybalt.utils.network

import com.laybalt.utils.network.data.*
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.io.IOException

object LayBaltAPI {
    const val BASE_URL = "https://api.noamm.org"

    suspend fun getDungeonStats(uuid: String) = apiRequest<DungeonStats>("/hypixel/dungeonstats/$uuid")
    suspend fun getSecrets(uuid: String) = apiRequest<Long>("/hypixel/secrets/$uuid")
    suspend fun getRtca(name: String) = apiRequest<RtcaData>("/hypixel/rtca/$name")
    suspend fun getStorage(uuid: String) = apiRequest<StorageData>("/hypixel/storage/$uuid")

    private suspend inline fun <reified T> apiRequest(path: String): Result<T> {
        if (ApiAuth.token == null) return Result.failure(LayBaltAPIException.Unauthorized("auth token is null"))
        val result = WebUtils.get("$BASE_URL$path") { header("Authorization", "Bearer ${ApiAuth.token}") }
        if (result.isFailure) return Result.failure(result.exceptionOrNull() !!)

        val res = result.getOrThrow()
        if (! res.status.isSuccess()) {
            val exception = when (res.status) {
                HttpStatusCode.TooManyRequests -> LayBaltAPIException.RateLimited()
                HttpStatusCode.BadGateway -> LayBaltAPIException.ApiUnavailable()
                HttpStatusCode.Unauthorized -> LayBaltAPIException.Unauthorized(res.bodyAsText())
                HttpStatusCode.Forbidden -> LayBaltAPIException.Forbidden(res.bodyAsText())
                else -> IOException("HTTP ${res.status.value}: ${res.bodyAsText()}")
            }

            return Result.failure(exception)
        }

        return runCatching { res.body() }
    }

    sealed class LayBaltAPIException(message: String): Exception(message) {
        class RateLimited: LayBaltAPIException("Hypixel API rate limit reached, try again later")
        class ApiUnavailable(message: String = "API is currently unavailable"): LayBaltAPIException(message)
        class Unauthorized(message: String): LayBaltAPIException(message)
        class Forbidden(message: String): LayBaltAPIException(message)
    }
}