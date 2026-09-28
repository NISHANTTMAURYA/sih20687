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

        // Seed initial data and sync latest session locations if online
        applicationScope.launch(Dispatchers.IO) {
            AttendanceDatabase.populateInitialData(this@AttendanceApplication, database)
            try {
                sessionRepository.syncSessionsFromServer(com.sih.faceattendance.data.remote.NetworkClient.apiService)
            } catch (_: Exception) {}
        }

        // Automatic Background Sync when internet connectivity is detected
        applicationScope.launch(Dispatchers.IO) {
            var wasOnline = networkMonitor.isOnline.value
            networkMonitor.isOnline.collect { isOnline ->
                if (isOnline && !wasOnline) {
                    try {
                        // 1. Download latest training center locations configured on server
                        sessionRepository.syncSessionsFromServer(NetworkClient.apiService)

                        // 2. Sync student roster bidirectionally
                        val studentSyncRes = studentRepository.syncWithServer(NetworkClient.apiService, this@AttendanceApplication)
                        if (studentSyncRes is StudentSyncResult.Success && studentSyncRes.pulledCount > 0) {
                            autoSyncEvent.emit("✓ Roster Updated: Pulled ${studentSyncRes.pulledCount} new student profile(s) from server!")
                        }

                        // 3. Upload pending offline attendance records
                        val pending = attendanceRepository.getPendingCount()
                        if (pending > 0) {
                            val syncRes = attendanceRepository.syncPendingRecords()
                            if (syncRes is com.sih.faceattendance.data.repository.SyncResult.Success) {
                                autoSyncEvent.emit("✓ Auto-Sync: ${syncRes.syncedCount} offline record(s) synced to Central Server!")
                            }
                        } else {
                            autoSyncEvent.emit("✓ Online: Synced latest center locations & roster from Central Server!")
                        }
                    } catch (_: Exception) {}
                }
                wasOnline = isOnline
            }
        }

        // Periodic Lightweight Heartbeat Loop (every 20s while online):
        // Automatically checks if newly enrolled students exist on server and pulls them
        applicationScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(20_000)
                if (networkMonitor.isOnline.value) {
                    try {
                        val verResp = NetworkClient.apiService.getRosterVersion()
                        if (verResp.isSuccessful && verResp.body() != null) {
                            val serverCount = verResp.body()!!.studentCount
                            val localCount = studentRepository.getCount()
                            if (serverCount != localCount) {
                                val syncRes = studentRepository.syncWithServer(NetworkClient.apiService, this@AttendanceApplication)
                                if (syncRes is StudentSyncResult.Success && syncRes.pulledCount > 0) {
                                    autoSyncEvent.emit("✓ Auto-Sync: Pulled ${syncRes.pulledCount} new student profile(s) from Central Server!")
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
