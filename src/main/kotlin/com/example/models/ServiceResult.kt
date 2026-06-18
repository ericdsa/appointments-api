package com.example.models

sealed class ServiceResult<out T> {
    data class Success<T>(val value: T) : ServiceResult<T>()
    data object NotFound : ServiceResult<Nothing>()
    data class ValidationError(val message: String) : ServiceResult<Nothing>()
    data class ExternalApiError(val message: String) : ServiceResult<Nothing>()
}
