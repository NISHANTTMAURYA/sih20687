package com.sih.faceattendance.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sih.faceattendance.core.*
import com.sih.faceattendance.ml.PipelineStage
import com.sih.faceattendance.ml.PipelineTelemetry

@Composable
fun HudOverlay(
    telemetry: PipelineTelemetry,
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // TOP BAR: System & Network Status
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Offline/Online Status Pill
            Surface(
                color = if (isOnline) DarkSlateSurface.copy(alpha = 0.85f) else AmberOffline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isOnline) CyanHUD else AmberOffline
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isOnline) Icons.Default.Security else Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = if (isOnline) CyanHUD else AmberOffline,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isOnline) "GATEWAY: ONLINE" else "SYSTEM: 100% OFFLINE",
                        color = if (isOnline) CyanHUD else AmberOffline,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Security Mode Pill
            Surface(
                color = DarkSlateSurface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkSlateBorder)
            ) {
                Text(
                    text = "AI ENGINE: ACTIVE",
                    color = EmeraldVerified,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // CENTER: Dynamic Targeting Reticle & Feedback
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(260.dp)
                .border(
                    width = 2.dp,
                    color = when (telemetry.stage) {
                        PipelineStage.SUCCESS -> EmeraldVerified
                        PipelineStage.REJECTED -> CrimsonAlert
                        PipelineStage.DETECTING_FACE -> if (telemetry.faceDetected) CyanHUD else DarkSlateBorder
                        else -> CyanHUD
                    },
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            // Corner Accents
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                Text(
                    text = "FRAME TARGET",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.TopStart)
                )
                if (telemetry.similarityScore > 0f) {
                    Text(
                        text = "${(telemetry.similarityScore * 100).toInt()}%",
                        color = if (telemetry.similarityScore >= telemetry.similarityThreshold) EmeraldVerified else CrimsonAlert,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            }
        }

        // BOTTOM: Multi-Stage Telemetry Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(16.dp))
                .background(DarkSlateSurface.copy(alpha = 0.92f))
                .border(1.dp, DarkSlateBorder, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pipeline Status Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = telemetry.statusMessage.uppercase(),
                    color = when (telemetry.stage) {
                        PipelineStage.SUCCESS -> EmeraldVerified
                        PipelineStage.REJECTED -> CrimsonAlert
                        else -> TextPrimary
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )

                if (telemetry.stage == PipelineStage.SUCCESS) {
                    Surface(
                        color = EmeraldVerified.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "VERIFIED",
                            color = EmeraldVerified,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (telemetry.failureReason != null && telemetry.stage == PipelineStage.REJECTED) {
                Text(
                    text = telemetry.failureReason,
                    color = CrimsonAlert,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Divider(color = DarkSlateBorder)

            // Step-by-Step Telemetry Matrix
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryCheckItem(
                    label = "FACE",
                    active = telemetry.faceDetected,
                    isSuccess = telemetry.faceDetected
                )
                TelemetryCheckItem(
                    label = "LIVENESS",
                    active = telemetry.faceDetected,
                    isSuccess = telemetry.livenessVerified
                )
                TelemetryCheckItem(
                    label = "NO PHONE",
                    active = telemetry.faceDetected,
                    isSuccess = !telemetry.phoneDetected
                )
                TelemetryCheckItem(
                    label = "MATCH",
                    active = telemetry.embeddingGenerated,
                    isSuccess = telemetry.matchFound
                )
                TelemetryCheckItem(
                    label = "GEOFENCE",
                    active = telemetry.matchFound,
                    isSuccess = telemetry.locationVerified
                )
            }
        }
    }
}

@Composable
private fun TelemetryCheckItem(
    label: String,
    active: Boolean,
    isSuccess: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val color = when {
            !active -> TextMuted
            isSuccess -> EmeraldVerified
            else -> CrimsonAlert
        }
        Icon(
            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Close,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
