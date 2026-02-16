package com.github.libretube.api.lounge

import com.github.libretube.BuildConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.JavaNetCookieJar
import okhttp3.logging.HttpLoggingInterceptor
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object LoungeApiFactory {
    private const val BASE_URL = "https://www.youtube.com/api/lounge/"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun create(client: OkHttpClient? = null): LoungeApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        val cookieManager = CookieManager().apply {
            // Lounge endpoints rely on the server-issued youtube_lounge_remote cookie.
            setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        }

        val httpClient = client ?: OkHttpClient.Builder()
            .cookieJar(JavaNetCookieJar(cookieManager))
            .addInterceptor(logging)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
        val contentType = "application/json".toMediaType()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(json.asConverterFactory(contentType))
            .client(httpClient)
            .build()
            .create(LoungeApiService::class.java)
    }
}
