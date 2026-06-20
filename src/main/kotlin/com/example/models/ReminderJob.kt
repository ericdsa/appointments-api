package com.example.models

enum class ReminderJobStatus { PENDING, PROCESSING, DONE, FAILED }

// A unit of work on the reminder queue. Lifecycle:
// PENDING -> (claimed) PROCESSING -> DONE, or back to PENDING for a retry,
// or FAILED once attempts are exhausted.
data class ReminderJob(
    val id: String,
    val appointmentId: String,
    val status: ReminderJobStatus,
    val attempts: Int,
    val scheduledFor: String,
    val lockedAt: String?,
    val lastError: String?,
    val createdAt: String,
    val updatedAt: String,
)
