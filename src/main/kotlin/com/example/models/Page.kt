package com.example.models

import kotlinx.serialization.Serializable

// Parsed pagination request. Not serialized; built from query params before it
// reaches the service, which validates the bounds.
data class PageRequest(val page: Int, val size: Int) {
    companion object {
        const val DEFAULT_PAGE = 1
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100
    }
}

@Serializable
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Long,
    val totalPages: Long,
)
