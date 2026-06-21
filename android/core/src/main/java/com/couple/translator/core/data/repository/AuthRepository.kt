package com.couple.translator.core.data.repository

import com.couple.translator.core.data.model.AuthDto
import com.couple.translator.core.network.SharedApiService
import org.json.JSONObject
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: SharedApiService,
    private val tokenStore: TokenStore,
) {
    private fun HttpException.errorMessage(): String {
        return try {
            val body = response()?.errorBody()?.string()
            val detail = JSONObject(body ?: "").optJSONObject("detail")
            detail?.optString("message") ?: "请求失败"
        } catch (_: Exception) {
            "请求失败 (${response()?.code() ?: "unknown"})"
        }
    }

    suspend fun login(email: String, password: String): Result<AuthDto.TokenResponse> {
        return try {
            val response = apiService.login(AuthDto.LoginRequest(email, password))
            if (response.isSuccess && response.data != null) {
                tokenStore.saveTokens(response.data.accessToken, response.data.refreshToken)
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.failure(Exception(e.errorMessage()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(email: String, password: String): Result<AuthDto.RegisterResponse> {
        return try {
            val response = apiService.register(AuthDto.RegisterRequest(email, password))
            if (response.isSuccess && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.failure(Exception(e.errorMessage()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun forgotPassword(email: String): Result<Unit> {
        return try {
            val response = apiService.forgotPassword(AuthDto.ForgotPasswordRequest(email))
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.failure(Exception(e.errorMessage()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resetPassword(email: String, code: String, newPassword: String): Result<Unit> {
        return try {
            val response = apiService.resetPassword(
                AuthDto.ResetPasswordRequest(email, code, newPassword)
            )
            if (response.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.failure(Exception(e.errorMessage()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        tokenStore.clearTokens()
    }

    suspend fun isLoggedIn(): Boolean = tokenStore.isLoggedIn()
}
