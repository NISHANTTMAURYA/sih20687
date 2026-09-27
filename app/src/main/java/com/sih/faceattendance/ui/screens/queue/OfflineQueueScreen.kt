package com.sih.faceattendance.ui.screens.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sih.faceattendance.AttendanceApplication
import com.sih.faceattendance.core.*
import com.sih.faceattendance.data.local.entities.AttendanceRecordEntity
import com.sih.faceattendance.data.local.entities.SyncStatus
import com.sih.faceattendance.data.remote.NetworkClient
import com.sih.faceattendance.data.repository.SyncResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineQueueScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AttendanceApplication
    val scope = rememberCoroutineScope()

    val allRecords by app.attendanceRepository.allRecordsFlow.collectAsState(initial = emptyList())
    val pendingCount by app.attendanceRepository.pendingCountFlow.collectAsState(initial = 0)
    val isOnline by app.networkMonitor.isOnline.collectAsState()

    var isSyncing by remember { mutableStateOf(false) }
    var syncFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var serverUrl by remember { mutableStateOf(NetworkClient.getBaseUrl()) }
    var showServerConfig by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "4. Offline Sync Queue",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Fault-Tolerant Store & Forward Gateway",
                            fontSize = 12.sp,
                            color = PrimaryBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showServerConfig = !showServerConfig }) {
                        Icon(Icons.Default.Settings, contentDescription = "Server Settings", tint = TextSecondary)
                    }
                    IconButton(
                        onClick = {
                            scope.launch { app.attendanceRepository.clearHistory() }
                        }
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Records", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LightSurface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(LightBackground)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Server URL Configuration Panel
            if (showServerConfig) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = LightSurface),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryBlue),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "CENTRAL SYNC GATEWAY ENDPOINT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue,
                            letterSpacing = 0.5.sp
                        )
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = {
                                serverUrl = it
                                NetworkClient.updateBaseUrl(it)
                            },
                            label = { Text("Gateway URL (Host PC IP)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = LightCardBorder
                            )
                        )
                        Text(
                            text = "Tip: For Android emulator use http://10.0.2.2:8000/ | For real phone use host Wi-Fi IP (http://192.168.29.209:8000/)",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Sync Status Summary Card
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSurface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (pendingCount > 0) AmberOffline.copy(alpha = 0.5f) else EmeraldVerified.copy(alpha = 0.5f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "OFFLINE QUEUE STATUS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (pendingCount > 0) AmberOffline else EmeraldVerified,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = if (pendingCount > 0) "$pendingCount Pending Records" else "Queue Empty — All Synced ✓",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Network State Pill
                        Surface(
                            color = if (isOnline) EmeraldContainer else AmberContainer,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isOnline) EmeraldVerified.copy(alpha = 0.3f) else AmberOffline.copy(alpha = 0.3f)
                            )
                        ) {
                            Text(
                                text = if (isOnline) "ONLINE" else "OFFLINE",
                                color = if (isOnline) EmeraldVerified else AmberOffline,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Sync Button Trigger
                    Button(
                        onClick = {
                            isSyncing = true
                            syncFeedbackMessage = null
                            scope.launch {
                                val result = app.attendanceRepository.syncPendingRecords()
                                isSyncing = false
                                syncFeedbackMessage = when (result) {
                                    is SyncResult.Success -> "${result.syncedCount} records synced successfully to central server ✓"
                                    is SyncResult.NoPendingRecords -> "No pending records to sync."
                                    is SyncResult.Error -> "Sync failed: ${result.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (pendingCount > 0) PrimaryBlue else LightSubtle
                        ),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("UPLOADING TO CENTRAL SERVER...", color = Color.White, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = if (pendingCount > 0) Color.White else TextMuted
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (pendingCount > 0) "SYNC NOW ($pendingCount PENDING)" else "QUEUE SYNCED",
                                color = if (pendingCount > 0) Color.White else TextMuted,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    syncFeedbackMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = if (msg.contains("successfully") || msg.contains("No pending")) EmeraldVerified else CrimsonAlert,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Attendance Record List
            Text(
                text = "LOCAL ATTENDANCE LEDGER (${allRecords.size} EVENTS)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                letterSpacing = 1.sp
            )

            if (allRecords.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No attendance marked yet.\nScan students in the 'Scan' tab to record attendance.",
                        color = TextMuted,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allRecords, key = { it.recordId }) { record ->
                        AttendanceRecordCard(record)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttendanceRecordCard(record: AttendanceRecordEntity) {
    val isSynced = record.syncStatus == SyncStatus.SYNCED
    val timeStr = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(record.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (isSynced) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                contentDescription = null,
                tint = if (isSynced) EmeraldVerified else AmberOffline,
                modifier = Modifier.size(24.dp)
            )

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = record.studentName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = timeStr,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }

                Text(
                    text = "${record.sessionTitle}  •  Match: ${(record.similarityScore * 100).toInt()}%  •  Liveness: ${(record.livenessScore * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            Surface(
                color = if (isSynced) EmeraldContainer else AmberContainer,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = if (isSynced) "SYNCED" else "PENDING",
                    color = if (isSynced) EmeraldVerified else AmberOffline,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
