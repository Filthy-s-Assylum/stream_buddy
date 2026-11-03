package com.streambuddy.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Shared OkHttp client. One client (and its connection pool) for the whole process. */
object Http {

    const val USER_AGENT = "StreamBuddy/1.0.0 (Android)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val withAgent = if (request.header("User-Agent") == null) {
                    request.newBuilder().header("User-Agent", USER_AGENT).build()
                } else {
                    request
                }
                chain.proceed(withAgent)
            }
            .build()
    }

    fun request(url: String): Request.Builder =
        Request.Builder().url(url).header("User-Agent", USER_AGENT)
}
