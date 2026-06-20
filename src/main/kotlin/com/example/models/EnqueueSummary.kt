package com.example.models

import kotlinx.serialization.Serializable

// Result of enqueuing reminder jobs: how many durable jobs were placed on the
// queue. The actual delivery outcome is reported later by the worker, not here.
@Serializable
data class EnqueueSummary(
    val enqueued: Int,
)
