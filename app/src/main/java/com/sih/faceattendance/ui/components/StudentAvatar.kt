package com.sih.faceattendance.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sih.faceattendance.core.LightCardBorderStrong
import com.sih.faceattendance.core.LightSubtle
import com.sih.faceattendance.core.PrimaryBlue
import com.sih.faceattendance.core.TextMuted
import java.io.File

@Composable
fun StudentAvatar(
    photoUri: String?,
    size: Dp = 56.dp,
    shapeCorner: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(photoUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(photoUri) {
        if (!photoUri.isNullOrEmpty()) {
            try {
                if (photoUri.startsWith("students/")) {
                    // Load from assets
                    val stream = context.assets.open(photoUri)
                    bitmap = BitmapFactory.decodeStream(stream)
                    stream.close()
                } else {
                    // Load from internal file storage
                    val file = File(photoUri)
                    if (file.exists()) {
                        bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    }
                }
            } catch (_: Exception) {
                bitmap = null
            }
        } else {
            bitmap = null
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(shapeCorner))
            .background(LightSubtle)
            .border(1.dp, LightCardBorderStrong, RoundedCornerShape(shapeCorner)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Student Photo",
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}
