package com.couple.translator.core.network

import android.content.Context
import com.couple.translator.core.data.repository.TokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // 默认 BASE_URL，可以通过 AppStartup 或配置覆盖
    const val DEFAULT_BASE_URL = "http://182.92.194.78:8000/"

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore {
        return TokenStore(context)
    }

    @Provides
    @Singleton
    fun provideTokenInterceptor(tokenStore: TokenStore): TokenInterceptor {
        return TokenInterceptor(tokenStore)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(tokenInterceptor: TokenInterceptor): OkHttpClient {
        // 两级日志：普通接口用 BODY（便于排查报文），流式接口只能用 BASIC。
        //
        // **为什么不能给 SSE 用 BODY 级别**：`HttpLoggingInterceptor` 在 BODY 级别下
        // 会执行 `source.request(Long.MAX_VALUE)`，也就是**把整个响应体读进内存**
        // 之后才把 Response 交出去。它对普通接口无感，但对 SSE 是致命的——
        // 逐字下发的流会被整段缓冲，用户看到的是「转圈十几秒 → 答案一次性蹦出来」，
        // 打点式逐字渲染完全失效。`@Streaming` 挡不住这一点：缓冲发生在
        // Retrofit 拿到 body 之前。
        val bodyLogging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val basicLogging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .addInterceptor(tokenInterceptor)
            .addInterceptor(StreamSafeLoggingInterceptor(bodyLogging, basicLogging))
            .connectTimeout(30, TimeUnit.SECONDS)
            // readTimeout 衡量的是「两次数据到达之间的间隔」，不是整次请求耗时。
            // AI 侧主模型是推理模型，思考期间 SSE 无字节下发（实测首字约 14s，
            // 长 prompt 更久），沿用 30s 会在模型思考时把连接判为超时断开。
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl(DEFAULT_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideSharedApiService(retrofit: Retrofit): SharedApiService {
        return retrofit.create(SharedApiService::class.java)
    }
}

/**
 * 按「是不是流式请求」把日志分派给两个不同级别的拦截器。
 *
 * 流式请求只做 BASIC 级日志（请求行 + 响应行 + 耗时），不碰响应体；
 * 其余请求照旧 BODY 级。判定用路径后缀 [SSE_PATH_SUFFIX]——
 * 项目里所有 SSE 端点都以 `/stream` 结尾（`ai/chat/stream`、
 * `ai/understand-letter/stream`）。**新增 SSE 端点必须沿用这个后缀**，
 * 否则会被 BODY 级日志整段缓冲，流式效果静默消失。
 */
private class StreamSafeLoggingInterceptor(
    private val bodyLogging: HttpLoggingInterceptor,
    private val basicLogging: HttpLoggingInterceptor,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val delegate =
            if (request.url.encodedPath.endsWith(SSE_PATH_SUFFIX)) basicLogging else bodyLogging
        return delegate.intercept(chain)
    }

    private companion object {
        const val SSE_PATH_SUFFIX = "/stream"
    }
}
