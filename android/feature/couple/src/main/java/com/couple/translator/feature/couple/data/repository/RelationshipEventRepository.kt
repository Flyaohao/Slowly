package com.couple.translator.feature.couple.data.repository

import com.couple.translator.feature.couple.data.model.RelationshipEventDto
import com.couple.translator.feature.couple.network.CoupleApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelationshipEventRepository @Inject constructor(
    private val apiService: CoupleApiService,
) {
    suspend fun listEvents(): Result<RelationshipEventDto.EventListResponse?> {
        return try {
            val response = apiService.getRelationshipEvents()
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getEvent(id: Long): Result<RelationshipEventDto.RelationshipEventResponse?> {
        return try {
            val response = apiService.getRelationshipEvent(id)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createEvent(
        request: RelationshipEventDto.CreateEventRequest,
    ): Result<RelationshipEventDto.RelationshipEventResponse?> {
        return try {
            val response = apiService.createRelationshipEvent(request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateEvent(
        id: Long,
        request: RelationshipEventDto.UpdateEventRequest,
    ): Result<RelationshipEventDto.RelationshipEventResponse?> {
        return try {
            val response = apiService.updateRelationshipEvent(id, request)
            if (response.isSuccess) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteEvent(id: Long): Result<Unit> {
        return try {
            val response = apiService.deleteRelationshipEvent(id)
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
