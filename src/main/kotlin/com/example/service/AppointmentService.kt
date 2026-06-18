package com.example.service

import com.example.models.Appointment
import com.example.models.AppointmentRequest
import com.example.models.ServiceResult

interface AppointmentService {
    suspend fun getAll(): ServiceResult<List<Appointment>>
    suspend fun getById(id: String): ServiceResult<Appointment>
    suspend fun create(req: AppointmentRequest): ServiceResult<Appointment>
    suspend fun update(id: String, req: AppointmentRequest): ServiceResult<Appointment>

    // Atomically replaces an appointment: deletes the old record and inserts the
    // new one in a single transaction so there is no window where neither exists.
    suspend fun reschedule(id: String, req: AppointmentRequest): ServiceResult<Appointment>

    suspend fun delete(id: String): ServiceResult<Unit>
}
