package com.example.db

import org.jetbrains.exposed.sql.Table

object ReminderJobsTable : Table("reminder_jobs") {
    val id = varchar("id", 36)
    val appointmentId = varchar("appointment_id", 36)
    val status = varchar("status", 20)
    val attempts = integer("attempts")
    val scheduledFor = varchar("scheduled_for", 50)
    val lockedAt = varchar("locked_at", 50).nullable()
    val lastError = text("last_error").nullable()
    val createdAt = varchar("created_at", 50)
    val updatedAt = varchar("updated_at", 50)

    override val primaryKey = PrimaryKey(id)
}
