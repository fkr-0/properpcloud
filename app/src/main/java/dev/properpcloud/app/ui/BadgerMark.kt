package dev.properpcloud.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun BadgerCloudMark(
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val secondary = androidx.compose.material3.MaterialTheme.colorScheme.secondary
    val surface = androidx.compose.material3.MaterialTheme.colorScheme.surface
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawOval(primary, topLeft = Offset(w * 0.12f, h * 0.18f), size = Size(w * 0.76f, h * 0.68f))
        val leftEar = Path().apply {
            moveTo(w * 0.20f, h * 0.30f)
            lineTo(w * 0.13f, h * 0.04f)
            lineTo(w * 0.39f, h * 0.22f)
            close()
        }
        val rightEar = Path().apply {
            moveTo(w * 0.80f, h * 0.30f)
            lineTo(w * 0.87f, h * 0.04f)
            lineTo(w * 0.61f, h * 0.22f)
            close()
        }
        drawPath(leftEar, primary)
        drawPath(rightEar, primary)
        val stripe = Path().apply {
            moveTo(w * 0.43f, h * 0.16f)
            lineTo(w * 0.33f, h * 0.68f)
            quadraticTo(w * 0.50f, h * 0.88f, w * 0.67f, h * 0.68f)
            lineTo(w * 0.57f, h * 0.16f)
            close()
        }
        drawPath(stripe, surface)
        drawCircle(surface, radius = w * 0.055f, center = Offset(w * 0.32f, h * 0.48f))
        drawCircle(surface, radius = w * 0.055f, center = Offset(w * 0.68f, h * 0.48f))
        drawCircle(secondary, radius = w * 0.055f, center = Offset(w * 0.50f, h * 0.68f))
        drawArc(
            color = secondary,
            startAngle = 205f,
            sweepAngle = 130f,
            useCenter = false,
            topLeft = Offset(w * 0.28f, h * 0.58f),
            size = Size(w * 0.44f, h * 0.28f),
            style = Stroke(width = w * 0.045f),
        )
        drawCircle(Color.White.copy(alpha = 0.18f), radius = w * 0.12f, center = Offset(w * 0.72f, h * 0.25f))
    }
}
