package com.bvb.android.di

import com.bvb.android.BuildConfig
import com.bvb.android.core.network.ApiService
import com.bvb.android.core.network.AuthInterceptor
import com.bvb.android.core.network.NullJsonInterceptor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        authInterceptor: AuthInterceptor,
        nullJsonInterceptor: NullJsonInterceptor,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // HTTP/2 multiplexes every request onto a single TCP connection. After
            // Doze or a NAT timeout that connection can die silently (half-open):
            // without an active probe OkHttp keeps sending new streams onto the
            // dead connection, so every screen's fetch hangs and the whole app
            // stays "loading" until a manual restart. pingInterval sends HTTP/2
            // PING frames and tears down (and evicts) the connection when no PONG
            // arrives, so the next request opens a healthy one. callTimeout caps
            // the total time of a call so it fails fast (and shows a retry)
            // instead of hanging on a stale socket.
            .pingInterval(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(nullJsonInterceptor)
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    // Never log bodies: they carry passwords and keys.
                }
            )
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl("${BuildConfig.BASE_URL}/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService = retrofit.create(ApiService::class.java)
}
