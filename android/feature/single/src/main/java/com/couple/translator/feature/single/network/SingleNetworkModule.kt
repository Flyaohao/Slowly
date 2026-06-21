package com.couple.translator.feature.single.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SingleNetworkModule {

    @Provides
    @Singleton
    fun provideSingleApiService(retrofit: Retrofit): SingleApiService {
        return retrofit.create(SingleApiService::class.java)
    }
}
