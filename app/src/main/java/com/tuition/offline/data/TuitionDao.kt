package com.tuition.offline.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TuitionDao {
    @Query("SELECT * FROM students WHERE status = 'ACTIVE' ORDER BY name")
    fun observeStudents(): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students WHERE studentId = :id")
    suspend fun student(id: String): StudentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStudent(student: StudentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFee(fee: FeeRecordEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPayment(payment: PaymentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCorrection(correction: PaymentCorrectionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertReversal(reversal: PaymentReversalEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAttendance(attendance: AttendanceEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun audit(log: AuditLogEntity)

    @Query("DELETE FROM students WHERE studentId = :id") suspend fun deleteStudent(id: String)

    @Query("SELECT * FROM fee_records WHERE studentId = :studentId AND billingPeriod = :period LIMIT 1")
    suspend fun fee(studentId: String, period: String): FeeRecordEntity?

    @Query("SELECT * FROM payments WHERE feeId = :feeId ORDER BY paymentDate DESC, createdAt DESC")
    suspend fun payments(feeId: String): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE feeId = :feeId ORDER BY paymentDate DESC, createdAt DESC")
    fun observePayments(feeId: String): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payment_corrections WHERE paymentId = :paymentId ORDER BY correctedAt DESC")
    suspend fun corrections(paymentId: String): List<PaymentCorrectionEntity>

    @Query("SELECT * FROM fee_records WHERE billingPeriod = :period ORDER BY studentId")
    suspend fun feesForPeriod(period: String): List<FeeRecordEntity>

    @Query("SELECT * FROM attendance WHERE studentId = :studentId AND date = :date LIMIT 1")
    suspend fun attendance(studentId: String, date: String): AttendanceEntity?

    @Query("SELECT * FROM attendance WHERE date = :date")
    suspend fun attendanceForDate(date: String): List<AttendanceEntity>

    @Query("SELECT * FROM students")
    suspend fun allStudents(): List<StudentEntity>
    @Query("SELECT * FROM fee_records")
    suspend fun allFees(): List<FeeRecordEntity>
    @Query("SELECT * FROM payments")
    suspend fun allPayments(): List<PaymentEntity>
    @Query("SELECT * FROM payment_corrections")
    suspend fun allCorrections(): List<PaymentCorrectionEntity>
    @Query("SELECT * FROM payment_reversals")
    suspend fun allReversals(): List<PaymentReversalEntity>
    @Query("SELECT * FROM attendance")
    suspend fun allAttendance(): List<AttendanceEntity>
    @Query("SELECT * FROM audit_log")
    suspend fun allAudit(): List<AuditLogEntity>

    @Query("DELETE FROM students") suspend fun clearStudents()
    @Query("DELETE FROM fee_records") suspend fun clearFees()
    @Query("DELETE FROM payments") suspend fun clearPayments()
    @Query("DELETE FROM payment_corrections") suspend fun clearCorrections()
    @Query("DELETE FROM payment_reversals") suspend fun clearReversals()
    @Query("DELETE FROM attendance") suspend fun clearAttendance()
    @Query("DELETE FROM audit_log") suspend fun clearAudit()

    @Transaction
    suspend fun clearAllAppData() {
        clearAudit(); clearAttendance(); clearReversals(); clearCorrections()
        clearPayments(); clearFees(); clearStudents()
    }
}
