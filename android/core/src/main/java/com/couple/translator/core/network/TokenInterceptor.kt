package com.couple.translator.core.network

import com.couple.translator.core.common.Constants
import com.couple.translator.core.data.repository.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = runBlocking { tokenStore.getToken() }

        val request = if (token != null) {
            original.newBuilder()
                .header(Constants.AUTH_HEADER, "${Constants.BEARER_PREFIX}$token")
                .build()
        } else {
            original
        }

        return chain.proceed(request)
    }
}
