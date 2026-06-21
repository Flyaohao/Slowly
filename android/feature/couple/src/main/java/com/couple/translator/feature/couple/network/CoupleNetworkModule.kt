package com.couple.translator.feature.couple.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoupleNetworkModule {

    @Provides
    @Singleton
    fun provideCoupleApiService(retrofit: Retrofit): CoupleApiService {
        return retrofit.create(CoupleApiService::class.java)
    }
}
