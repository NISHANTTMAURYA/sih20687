package com.sih.faceattendance.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sih.faceattendance.data.local.dao.AttendanceDao
import com.sih.faceattendance.data.local.dao.SessionDao
import com.sih.faceattendance.data.local.dao.StudentDao
import com.sih.faceattendance.data.local.entities.AttendanceRecordEntity
import com.sih.faceattendance.data.local.entities.SessionEntity
import com.sih.faceattendance.data.local.entities.StudentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStreamReader

@Database(
    entities = [
        StudentEntity::class,
        SessionEntity::class,
        AttendanceRecordEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AttendanceDatabase : RoomDatabase() {

    abstract fun studentDao(): StudentDao
    abstract fun sessionDao(): SessionDao
    abstract fun attendanceDao(): AttendanceDao

    companion object {
        @Volatile
        private var INSTANCE: AttendanceDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): AttendanceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AttendanceDatabase::class.java,
                    "ncct_attendance.db"
                )
                    .addCallback(DatabaseCallback(context.applicationContext, scope))
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(
            private val context: Context,
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) {
                        populateInitialData(context, database)
                    }
                }
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) {
                        populateInitialData(context, database)
                    }
                }
            }
        }

        private data class StudentJsonModel(
            val studentId: String,
            val name: String,
            val rollNumber: String,
            val course: String,
            val enrolledSessionIds: List<String>,
            val faceEmbedding: List<Float>,
            val photoPath: String?
        )

        suspend fun populateInitialData(context: Context, database: AttendanceDatabase) {
            val sessionDao = database.sessionDao()
            val studentDao = database.studentDao()

            if (sessionDao.getSessionCount() == 0) {
                sessionDao.insertSessions(
                    listOf(
                        SessionEntity(
                            sessionId = "DL-01",
                            title = "Digital Literacy",
                            batchCode = "DL-01",
                            startTime = "09:00 AM",
                            endTime = "11:00 AM",
                            centerName = "NCCT Regional Training Center, Sector 5",
                            centerLatitude = 19.0760,
                            centerLongitude = 72.8777,
                            allowedRadiusMeters = 100.0f,
                            isActive = true
                        ),
                        SessionEntity(
                            sessionId = "CM-02",
                            title = "Cooperative Management",
                            batchCode = "CM-02",
                            startTime = "11:30 AM",
                            endTime = "01:30 PM",
                            centerName = "NCCT State Institute, Hall B",
                            centerLatitude = 19.0760,
                            centerLongitude = 72.8777,
                            allowedRadiusMeters = 100.0f,
                            isActive = true
                        ),
                        SessionEntity(
                            sessionId = "EN-03",
                            title = "Entrepreneurship Development",
                            batchCode = "EN-03",
                            startTime = "02:00 PM",
                            endTime = "04:00 PM",
                            centerName = "NCCT Enterprise Lab, Hub 3",
                            centerLatitude = 19.0760,
                            centerLongitude = 72.8777,
                            allowedRadiusMeters = 100.0f,
                            isActive = true
                        ),
                        SessionEntity(
                            sessionId = "AB-04",
                            title = "Agri-Cooperative Banking",
                            batchCode = "AB-04",
                            startTime = "04:30 PM",
                            endTime = "06:30 PM",
                            centerName = "NCCT Rural Development Center",
                            centerLatitude = 19.0760,
                            centerLongitude = 72.8777,
                            allowedRadiusMeters = 100.0f,
                            isActive = true
                        ),
                        SessionEntity(
                            sessionId = "RC-05",
                            title = "Rural Credit & Finance",
                            batchCode = "RC-05",
                            startTime = "07:00 PM",
                            endTime = "09:00 PM",
                            centerName = "NCCT Microfinance Hall",
                            centerLatitude = 19.0760,
                            centerLongitude = 72.8777,
                            allowedRadiusMeters = 100.0f,
                            isActive = true
                        )
                    )
                )
            }

            if (studentDao.getStudentCount() < 30) {
                try {
                    val assetStream = context.assets.open("students/students_dataset.json")
                    val reader = InputStreamReader(assetStream)
                    val type = object : TypeToken<List<StudentJsonModel>>() {}.type
                    val studentsJson: List<StudentJsonModel> = Gson().fromJson(reader, type)
                    reader.close()

                    val entities = studentsJson.map { s ->
                        StudentEntity(
                            studentId = s.studentId,
                            name = s.name,
                            rollNumber = s.rollNumber,
                            course = s.course,
                            enrolledSessionIds = s.enrolledSessionIds,
                            faceEmbedding = s.faceEmbedding.toFloatArray(),
                            photoUri = s.photoPath,
                            createdAt = System.currentTimeMillis()
                        )
                    }
                    studentDao.insertStudents(entities)
                } catch (_: Exception) {
                    // Fallback to default student if dataset file is absent
                    val defaultVector = FloatArray(128) { 0.05f }
                    studentDao.insertStudent(
                        StudentEntity(
                            studentId = "NCCT1001",
                            name = "Nishant Maurya",
                            rollNumber = "101",
                            course = "Digital Literacy",
                            enrolledSessionIds = listOf("DL-01"),
                            faceEmbedding = defaultVector,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }
}
