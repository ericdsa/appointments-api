package com.example.db

import org.jetbrains.exposed.sql.Table

object AppointmentsTable : Table("appointments") {
    val id = varchar("id", 36)
    val title = varchar("title", 255)
    val description = text("description")
    val scheduledAt = varchar("scheduled_at", 50)
    val durationMinutes = integer("duration_minutes")
    val attendee = varchar("attendee", 255)

    override val primaryKey = PrimaryKey(id)
}
