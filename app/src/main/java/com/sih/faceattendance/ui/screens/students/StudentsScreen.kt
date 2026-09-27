package com.sih.faceattendance.ui.screens.students

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.sih.faceattendance.data.local.AttendanceDatabase
import com.sih.faceattendance.data.local.entities.StudentEntity
import com.sih.faceattendance.ui.components.StudentAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentsScreen(
    onNavigateToEnrollment: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AttendanceApplication
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val students by app.studentRepository.allStudentsFlow.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var selectedStudentForDetail by remember { mutableStateOf<StudentEntity?>(null) }

    // Deletion Dialog States
    var studentToDelete by remember { mutableStateOf<StudentEntity?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showResetSeedDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val filteredStudents = remember(students, searchQuery) {
        if (searchQuery.isBlank()) {
            students
        } else {
            students.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.studentId.contains(searchQuery, ignoreCase = true) ||
                it.course.contains(searchQuery, ignoreCase = true) ||
                it.rollNumber.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "5. Local Enrolled Database",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${students.size} Verified Biometric Profiles in Room SQLite",
                            fontSize = 12.sp,
                            color = PrimaryBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToEnrollment) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Enrol Student", tint = PrimaryBlue)
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = TextSecondary)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.background(LightSurface)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Reset to 30 Seed Profiles", fontSize = 13.sp) },
                                leadingIcon = {
                                    Icon(Icons.Default.RestartAlt, contentDescription = null, tint = PrimaryBlue)
                                },
                                onClick = {
                                    menuExpanded = false
                                    showResetSeedDialog = true
                                }
                            )
                            Divider(color = LightCardBorder)
                            DropdownMenuItem(
                                text = { Text("Delete All Students", fontSize = 13.sp, color = Color(0xFFEF4444)) },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFEF4444))
                                },
                                onClick = {
                                    menuExpanded = false
                                    showClearAllDialog = true
                                }
                            )
                        }
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
            // Institutional SQLite Stats Banner
            Surface(
                color = PrimaryBlueContainer,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(24.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "LOCAL ROOM SQLITE DATABASE ENGINE",
                            color = PrimaryBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Biometric profiles with 128-dim ArcFace embeddings stored 100% offline.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Surface(
                        color = EmeraldContainer,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldVerified.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "${students.size} ACTIVE",
                            color = EmeraldVerified,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Search Bar & Action Shortcuts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search by name, roll no, or ID...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LightSurface,
                        unfocusedContainerColor = LightSurface,
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = LightCardBorder
                    )
                )
            }

            // Empty state if database is empty or no match
            if (filteredStudents.isEmpty()) {
                Surface(
                    color = LightSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.PersonOff,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (searchQuery.isNotBlank()) "No students match '$searchQuery'" else "No Student Biometric Records",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (searchQuery.isNotBlank()) "Try refining your search keyword" else "The local SQLite database has no enrolled students. You can restore the 30 default seed profiles or enroll new students.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        AttendanceDatabase.populateInitialData(context, app.database)
                                        snackbarHostState.showSnackbar("Restored 30 seed student profiles")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Restore 30 Seed Profiles", fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                // Students List
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredStudents, key = { it.studentId }) { student ->
                        StudentCard(
                            student = student,
                            isSelected = selectedStudentForDetail?.studentId == student.studentId,
                            onClick = {
                                selectedStudentForDetail = if (selectedStudentForDetail?.studentId == student.studentId) null else student
                            },
                            onDelete = {
                                studentToDelete = student
                            }
                        )
                    }
                }
            }
        }
    }

    // Confirmation Dialog: Single Student Deletion
    if (studentToDelete != null) {
        val student = studentToDelete!!
        AlertDialog(
            onDismissRequest = { studentToDelete = null },
            icon = {
                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(28.dp))
            },
            title = {
                Text(text = "Delete Student Profile?", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            },
            text = {
                Text(
                    text = "Are you sure you want to permanently delete \"${student.name}\" (ID: ${student.studentId}, Roll: ${student.rollNumber}) and their 128-dim biometric facial embedding from the local database?",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        studentToDelete = null
                        scope.launch(Dispatchers.IO) {
                            app.studentRepository.deleteStudent(student.studentId)
                            snackbarHostState.showSnackbar("Deleted ${student.name} from database")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { studentToDelete = null },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = LightSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Confirmation Dialog: Clear All Students
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            icon = {
                Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(28.dp))
            },
            title = {
                Text(text = "Clear Entire Student Database?", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            },
            text = {
                Text(
                    text = "This will permanently delete all ${students.size} student biometric profiles and embeddings from the offline Room SQLite database. This action cannot be undone.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearAllDialog = false
                        scope.launch(Dispatchers.IO) {
                            app.studentRepository.deleteAllStudents()
                            snackbarHostState.showSnackbar("All students deleted from local database")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Clear All Data", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearAllDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = LightSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Confirmation Dialog: Reset to 30 Seed Profiles
    if (showResetSeedDialog) {
        AlertDialog(
            onDismissRequest = { showResetSeedDialog = false },
            icon = {
                Icon(Icons.Default.RestartAlt, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(28.dp))
            },
            title = {
                Text(text = "Reset Database to 30 Seed Profiles?", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            },
            text = {
                Text(
                    text = "This will wipe the current biometric roster and restore the original 30 real student profiles with pre-computed ArcFace embeddings from the offline dataset.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetSeedDialog = false
                        scope.launch(Dispatchers.IO) {
                            app.studentRepository.deleteAllStudents()
                            AttendanceDatabase.populateInitialData(context, app.database)
                            snackbarHostState.showSnackbar("Database restored with 30 seed student profiles")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Reset & Restore", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetSeedDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = LightSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun StudentCard(
    student: StudentEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = LightSurface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) PrimaryBlue else LightCardBorder
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 3.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StudentAvatar(
                    photoUri = student.photoUri,
                    size = 56.dp,
                    shapeCorner = 12.dp
                )

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = student.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Surface(
                            color = LightSubtle,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder)
                        ) {
                            Text(
                                text = "Roll ${student.rollNumber}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = "ID: ${student.studentId}  •  ${student.course}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    // Enrolled session badges
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        student.enrolledSessionIds.forEach { sessionId ->
                            Surface(
                                color = PrimaryBlueContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = sessionId,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryBlue,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Delete individual student button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete Student",
                        tint = Color(0xFFEF4444).copy(alpha = 0.75f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expanded Biometric Vector Inspection
            if (isSelected) {
                Divider(color = LightCardBorder)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "128-DIM ARCFACE BIOMETRIC SIGNATURE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "L2 Norm: 1.000",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = EmeraldVerified
                        )
                    }

                    Surface(
                        color = LightSubtle,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder)
                    ) {
                        val snippet = student.faceEmbedding.take(8).joinToString(", ") { String.format("%.3f", it) }
                        Text(
                            text = "[$snippet, ... +120 dimensions]",
                            fontSize = 11.sp,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Text(
                        text = "Photo stored: ${student.photoUri ?: "Default Asset"} (Offline)",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}
