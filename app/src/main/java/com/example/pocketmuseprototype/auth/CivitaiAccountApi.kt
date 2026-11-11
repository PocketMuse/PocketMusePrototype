package com.example.pocketmuseprototype.auth

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

internal const val CIVITAI_CLIENT_ID = "33f359b1-7721-4e44-9b3e-530bb0734a89"
internal const val CIVITAI_OAUTH_BASE = "https://auth.civitai.com/api/auth/oauth/"

internal class CivitaiAccountApi(
    client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
) {
    private val service = Retrofit.Builder()
        .baseUrl(CIVITAI_OAUTH_BASE)
        .client(client)
        .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CivitaiAccountService::class.java)

    suspend fun userInfo(accessToken: String): CivitaiProfile {
        val response = try {
            service.userInfo("Bearer $accessToken")
        } catch (_: SerializationException) {
            throw IOException("Invalid Civitai profile response")
        }
        response.errorBody()?.close()
        if (response.code() == 401) throw SessionRejected()
        if (!response.isSuccessful) throw IOException("Civitai profile request failed")
        val claims = response.body() ?: throw IOException("Missing Civitai profile response")
        val id = claims.textClaim("sub") ?: claims.textClaim("id")
            ?: (claims["id"] as? JsonPrimitive)?.longOrNull?.toString()
        val username = claims.textClaim("username") ?: claims.textClaim("preferred_username")
        if (id == null || username == null) throw IOException("Incomplete Civitai profile")
        return CivitaiProfile(id, username)
    }

    suspend fun revoke(refreshToken: String) {
        val response = service.revoke(refreshToken, "refresh_token", CIVITAI_CLIENT_ID)
        response.body()?.close()
        response.errorBody()?.close()
        if (!response.isSuccessful) throw IOException("Civitai revocation request failed")
    }
}

private fun JsonObject.textClaim(name: String): String? = (this[name] as? JsonPrimitive)
    ?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private interface CivitaiAccountService {
    @GET("userinfo")
    suspend fun userInfo(@Header("Authorization") bearer: String): Response<JsonObject>

    @FormUrlEncoded
    @POST("revoke")
    suspend fun revoke(
        @Field("token") token: String,
        @Field("token_type_hint") type: String,
        @Field("client_id") clientId: String
    ): Response<ResponseBody>
}
