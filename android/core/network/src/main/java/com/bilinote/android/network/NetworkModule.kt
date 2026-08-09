package com.bilinote.android.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object NetworkModule {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun createClient(token: String? = null): OkHttpClient {
        val normalizedToken = token?.trim()?.takeIf { it.isNotEmpty() }
        return OkHttpClient.Builder()
            .apply {
                if (normalizedToken != null) {
                    addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Authorization", "Bearer $normalizedToken")
                            .build()
                        chain.proceed(request)
                    }
                }
            }
            .build()
    }

    fun createRetrofit(baseUrl: String, token: String? = null): Retrofit {
        val normalizedBaseUrl = "${BackendUrl.normalize(baseUrl)}/"
        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(createClient(token))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
