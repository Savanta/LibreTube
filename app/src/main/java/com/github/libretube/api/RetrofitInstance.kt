package com.github.libretube.api

import android.util.Log
import com.github.libretube.BuildConfig
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create

object RetrofitInstance {
    const val PIPED_API_URL = "https://pipedapi.kavin.rocks"

    val authUrl
        get() = if (
            PreferenceHelper.getBoolean(
                PreferenceKeys.AUTH_INSTANCE_TOGGLE,
                false
            )
        ) {
           PreferenceHelper.getString(
                PreferenceKeys.AUTH_INSTANCE,
                PIPED_API_URL
            )
        } else {
            PipedMediaServiceRepository.apiUrl
        }

    val apiLazyMgr = resettableManager()
    val kotlinxConverterFactory = JsonHelper.json
        .asConverterFactory("application/json".toMediaType())

    val httpClient by lazy { buildClient() }

    val authApi by resettableLazy(apiLazyMgr) {
        buildRetrofitInstance<PipedAuthApi>(authUrl)
    }

    // the url provided here isn't actually used anywhere in the external api
    val externalApi = buildRetrofitInstance<ExternalApi>(PIPED_API_URL)

    private fun buildClient(): OkHttpClient {
        val httpClient = OkHttpClient().newBuilder()

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            httpClient.addInterceptor(loggingInterceptor)

            httpClient.addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)

                if (!request.url.encodedPath.startsWith("/streams/")) return@addInterceptor response

                val bodyString = response.body?.string().orEmpty()
                val preview = if (bodyString.length > 16000) bodyString.take(16000) + "…<truncated>" else bodyString
                Log.d("StreamsRaw", "${request.url} len=${bodyString.length} body=${preview}")

                val contentType = response.body?.contentType()
                return@addInterceptor response.newBuilder()
                    .body(bodyString.toResponseBody(contentType))
                    .build()
            }
        }

        return httpClient.build()
    }

    inline fun <reified T: Any> buildRetrofitInstance(apiUrl: String): T = Retrofit.Builder()
        .baseUrl(apiUrl)
        .client(httpClient)
        .addConverterFactory(kotlinxConverterFactory)
        .build()
        .create<T>()
}
