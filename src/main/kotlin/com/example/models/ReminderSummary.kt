package com.example.models

import kotlinx.serialization.Serializable

// Result of a bulk reminder run: how many attendee notifications were attempted
// and how many of those succeeded vs. failed.
@Serializable
data class ReminderSummary(
    val total: Int,
    val sent: Int,
    val failed: Int,
)
