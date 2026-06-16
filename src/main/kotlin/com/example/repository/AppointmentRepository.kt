package com.example.repository

import com.example.models.Appointment
import com.example.models.AppointmentRequest

interface AppointmentRepository {
    suspend fun findAll(): List<Appointment>
    suspend fun findById(id: String): Appointment?
    suspend fun create(req: AppointmentRequest): Appointment
    suspend fun update(id: String, req: AppointmentRequest): Appointment?
    suspend fun delete(id: String): Boolean
}
