package com.sih.faceattendance

import android.app.Application
import com.sih.faceattendance.core.LocationHelper
import com.sih.faceattendance.core.NetworkMonitor
import com.sih.faceattendance.data.local.AttendanceDatabase
import com.sih.faceattendance.data.remote.NetworkClient
import com.sih.faceattendance.data.repository.AttendanceRepository
import com.sih.faceattendance.data.repository.SessionRepository
import com.sih.faceattendance.data.repository.StudentRepository
import com.sih.faceattendance.data.repository.StudentSyncResult
import com.sih.faceattendance.ml.AttendancePipelineCoordinator
import com.sih.faceattendance.ml.FaceDetectorEngine
import com.sih.faceattendance.ml.FaceEmbeddingEngine
import com.sih.faceattendance.ml.LivenessEngine
import com.sih.faceattendance.ml.PhoneDetectorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AttendanceApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var database: AttendanceDatabase
        private set

    lateinit var studentRepository: StudentRepository
        private set

    lateinit var sessionRepository: SessionRepository
        private set

    lateinit var attendanceRepository: AttendanceRepository
        private set

    lateinit var faceDetectorEngine: FaceDetectorEngine
        private set

    lateinit var livenessEngine: LivenessEngine
        private set

    lateinit var phoneDetectorEngine: PhoneDetectorEngine
        private set

    lateinit var faceEmbeddingEngine: FaceEmbeddingEngine
        private set

    lateinit var pipelineCoordinator: AttendancePipelineCoordinator
        private set

    lateinit var locationHelper: LocationHelper
        private set

    lateinit var networkMonitor: NetworkMonitor
        private set

    val autoSyncEvent = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 5)

    override fun onCreate() {
        super.onCreate()

        // Local SQLite/Room DB
        database = AttendanceDatabase.getDatabase(this, applicationScope)
        studentRepository = StudentRepository(database.studentDao())
        sessionRepository = SessionRepository(database.sessionDao())
        attendanceRepository = AttendanceRepository(database.attendanceDao(), NetworkClient.apiService)

        // ML Inference Engines
        faceDetectorEngine = FaceDetectorEngine()
        livenessEngine = LivenessEngine(this)
        phoneDetectorEngine = PhoneDetectorEngine(this)
        faceEmbeddingEngine = FaceEmbeddingEngine(this)

        pipelineCoordinator = AttendancePipelineCoordinator(
            faceDetector = faceDetectorEngine,
            livenessEngine = livenessEngine,
            phoneDetector = phoneDetectorEngine,
            embeddingEngine = faceEmbeddingEngine,
            studentRepository = studentRepository,
            attendanceRepository = attendanceRepository
        )

        // Device Sensors & State
        locationHelper = LocationHelper(this)
        networkMonitor = NetworkMonitor(this)

        // Seed initial data and immediately sync latest sessions, students & attendance on startup
        applicationScope.launch(Dispatchers.IO) {
            AttendanceDatabase.populateInitialData(this@AttendanceApplication, database)
            try {
                sessionRepository.syncSessionsFromServer(NetworkClient.apiService)
            } catch (_: Exception) {}
            try {
                val studentSyncRes = studentRepository.syncWithServer(NetworkClient.apiService, this@AttendanceApplication)
                if (studentSyncRes is StudentSyncResult.Success && studentSyncRes.pulledCount > 0) {
                    autoSyncEvent.emit("✓ Auto-Sync: Pulled ${studentSyncRes.pulledCount} student profile(s) from Central Server!")
                }
            } catch (_: Exception) {}
            try {
                attendanceRepository.syncPendingRecords()
            } catch (_: Exception) {}
        }

        // Automatic Background Sync when internet connectivity is detected
        applicationScope.launch(Dispatchers.IO) {
            var wasOnline = false // Default to false so initial connection on app start triggers sync
            networkMonitor.isOnline.collect { isOnline ->
                if (isOnline) {
                    try {
                        // 1. Upload pending offline attendance records immediately
                        val pending = attendanceRepository.getPendingCount()
                        if (pending > 0) {
                            val syncRes = attendanceRepository.syncPendingRecords()
                            if (syncRes is com.sih.faceattendance.data.repository.SyncResult.Success) {
                                autoSyncEvent.emit("✓ Auto-Sync: ${syncRes.syncedCount} record(s) synced to Central Server!")
                            }
                        }

                        // If transitioning from offline to online, perform full synchronization
                        if (!wasOnline) {
                            sessionRepository.syncSessionsFromServer(NetworkClient.apiService)
                            val studentSyncRes = studentRepository.syncWithServer(NetworkClient.apiService, this@AttendanceApplication)
                            if (studentSyncRes is StudentSyncResult.Success && studentSyncRes.pulledCount > 0) {
                                autoSyncEvent.emit("✓ Roster Updated: Pulled ${studentSyncRes.pulledCount} student profile(s) from server!")
                            } else if (pending == 0) {
                                autoSyncEvent.emit("✓ Online: Central Server connected & in sync!")
                            }
                        }
                    } catch (_: Exception) {}
                }
                wasOnline = isOnline
            }
        }

        // Periodic Lightweight Heartbeat Loop (every 10s while online):
        // Automatically uploads pending attendance records and pulls new student enrollments
        applicationScope.launch(Dispatchers.IO) {
            val syncPrefs = getSharedPreferences("student_sync_prefs", android.content.Context.MODE_PRIVATE)
            while (true) {
                kotlinx.coroutines.delay(10_000)
                if (networkMonitor.isOnline.value) {
                    try {
                        // 1. Continuous Auto-Sync of any pending attendance records
                        val pending = attendanceRepository.getPendingCount()
                        if (pending > 0) {
                            val syncRes = attendanceRepository.syncPendingRecords()
                            if (syncRes is com.sih.faceattendance.data.repository.SyncResult.Success) {
                                autoSyncEvent.emit("✓ Auto-Sync: ${syncRes.syncedCount} record(s) synced to Central Server!")
                            }
                        }

                        // 2. Check if newly enrolled students exist on central server or roster version changed
                        val verResp = NetworkClient.apiService.getRosterVersion()
                        if (verResp.isSuccessful && verResp.body() != null) {
                            val serverCount = verResp.body()!!.studentCount
                            val serverVersion = verResp.body()!!.rosterVersion
                            val localCount = studentRepository.getCount()
                            val localVersion = syncPrefs.getLong("last_roster_version", -1L)

                            if (serverCount != localCount || serverVersion != localVersion || localVersion == -1L) {
                                val syncRes = studentRepository.syncWithServer(NetworkClient.apiService, this@AttendanceApplication)
                                if (syncRes is StudentSyncResult.Success && syncRes.pulledCount > 0) {
                                    autoSyncEvent.emit("✓ Auto-Sync: Pulled ${syncRes.pulledCount} updated profile(s) from Central Server!")
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        faceDetectorEngine.close()
        livenessEngine.close()
        phoneDetectorEngine.close()
        faceEmbeddingEngine.close()
    }
}
