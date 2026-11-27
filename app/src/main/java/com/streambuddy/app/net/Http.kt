package com.streambuddy.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared OkHttp client. One client (and its connection pool) for the whole process. */
object Http {

    const val USER_AGENT = "StreamBuddy/1.0.0 (Android)"

    private object HttpsOriginTag

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(NetworkGuard.interceptor())
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                if (original.header("User-Agent") == null) {
                    builder.header("User-Agent", USER_AGENT)
                }
                if (original.url.isHttps) {
                    builder.tag(HttpsOriginTag::class.java, HttpsOriginTag)
                }
                val response = chain.proceed(builder.build())
                if (original.url.isHttps && !response.request.url.isHttps) {
                    response.close()
                    throw IOException("Blocked an HTTPS-to-HTTP redirect")
                }
                response
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.tag(HttpsOriginTag::class.java) != null && !request.url.isHttps) {
                    throw IOException("Blocked an HTTPS-to-HTTP redirect")
                }
                chain.proceed(request)
            }
            .build()
    }

    /**
     * Long-lived streaming/download client sharing [client]'s connection pool,
     * VPN gate and HTTPS downgrade protection without a 90s whole-call timeout.
     */
    val mediaClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun request(url: String): Request.Builder =
        Request.Builder().url(url).header("User-Agent", USER_AGENT)
}
