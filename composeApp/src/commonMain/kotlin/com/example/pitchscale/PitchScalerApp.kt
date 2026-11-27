package com.example.pitchscale

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

val NeonCyan = Color(0xFF00F2FE)
val NeonBlue = Color(0xFF4FACFE)
val NeonRed = Color(0xFFE21143)
val DarkBg = Color(0xFF0F0F12)
val DarkSurface = Color(0xFF1E1E24)

@Composable
fun PitchScalerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonCyan,
            background = DarkBg,
            surface = DarkSurface
        ),
        content = content
    )
}

@Composable
fun PitchScalerApp(controller: PitchEngineController) {
    PitchScalerTheme {
        PitchScalerDashboard(controller = controller)
    }
}

@Composable
fun PitchScalerDashboard(controller: PitchEngineController) {
    val isRunning by controller.isRunning
    val semitones by controller.semitones
    val amplitude by controller.amplitude
    val statusText by controller.statusText
    val selectedMode by controller.selectedModeId
    val showRoutingAlert = remember { mutableStateOf(false) }

    val notice = controller.startRoutingNotice
    if (showRoutingAlert.value && notice != null) {
        AlertDialog(
            onDismissRequest = { showRoutingAlert.value = false },
            title = { Text(notice.title, color = Color.White) },
            text = { Text(notice.message, color = Color.LightGray) },
            confirmButton = {
                TextButton(onClick = {
                    showRoutingAlert.value = false
                    controller.startEngine()
                }) {
                    Text(notice.confirmLabel, color = NeonCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRoutingAlert.value = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = DarkSurface
        )
    }

    val compact = controller.hasEmbeddedPlayer

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .safeDrawingPadding()
            .padding(horizontal = if (compact) 16.dp else 24.dp, vertical = if (compact) 12.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header + Mode selector
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = if (compact) 4.dp else 16.dp)
        ) {
            Text(
                text = "PITCH SCALER",
                fontSize = if (compact) 20.sp else 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = statusText,
                fontSize = 13.sp,
                color = if (isRunning) NeonCyan else Color(0x66FFFFFF),
                fontWeight = FontWeight.Medium
            )

            if (controller.modeOptions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                ModeSelectorRow(
                    options = controller.modeOptions,
                    selectedId = selectedMode,
                    onSelect = { controller.selectMode(it) }
                )
            }
        }

        // Center content: either full SiriWaveform (Android) or Embedded Browser + compact waveform (iOS)
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF2E2E38), RoundedCornerShape(16.dp))
                ) {
                    controller.PlatformMainContent()
                }
                Spacer(modifier = Modifier.height(8.dp))
                SiriWaveform(
                    amplitude = amplitude,
                    isRunning = isRunning,
                    height = 56.dp
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                SiriWaveform(
                    amplitude = amplitude,
                    isRunning = isRunning,
                    height = 220.dp
                )
            }
        }

        // Pitch controls + Power button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = if (compact) 8.dp else 32.dp)
        ) {
            Text(
                text = PitchConfig.formatSemitones(semitones),
                fontSize = if (compact) 24.sp else 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(if (compact) 12.dp else 24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularControlButton(
                    symbol = "-",
                    enabled = isRunning,
                    size = if (compact) 56.dp else 72.dp,
                    onClick = { controller.decrementSemitones() }
                )

                Spacer(modifier = Modifier.width(if (compact) 36.dp else 48.dp))

                CircularControlButton(
                    symbol = "+",
                    enabled = isRunning,
                    size = if (compact) 56.dp else 72.dp,
                    onClick = { controller.incrementSemitones() }
                )
            }

            Spacer(modifier = Modifier.height(if (compact) 14.dp else 48.dp))

            val powerGradient = if (isRunning) {
                Brush.linearGradient(listOf(NeonRed, Color(0xFFFF5252)))
            } else {
                Brush.linearGradient(listOf(NeonCyan, NeonBlue))
            }

            Box(
                modifier = Modifier
                    .height(if (compact) 48.dp else 56.dp)
                    .fillMaxWidth(if (compact) 0.85f else 0.7f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(powerGradient)
                    .clickable {
                        if (isRunning) {
                            controller.stopEngine()
                        } else if (controller.startRoutingNotice != null) {
                            showRoutingAlert.value = true
                        } else {
                            controller.startEngine()
                        }
                    }
                    .shadow(8.dp, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isRunning) "STOP ENGINE" else "START ENGINE",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun ModeSelectorRow(
    options: List<ModeOption>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            val selected = option.id == selectedId
            val bg = if (selected) Color(0x2200F2FE) else DarkSurface
            val border = if (selected) NeonCyan else Color(0xFF2E2E38)
            val textColor = if (selected) NeonCyan else Color(0xAAFFFFFF)

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(20.dp))
                    .clickable { onSelect(option.id) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = option.label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor
                )
            }
        }
    }
}

@Composable
fun SiriWaveform(
    amplitude: Float,
    isRunning: Boolean,
    height: Dp = 220.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform_anim")
    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase1"
    )
    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (-2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val width = size.width
        val canvasHeight = size.height
        val centerY = canvasHeight / 2f

        if (isRunning && amplitude > 0.005f) {
            val colors = listOf(
                Color(0x2200F2FE),
                Color(0x557F00FF),
                Color(0x9900F2FE),
                NeonRed
            )
            val phases = listOf(phase1, phase2, phase1 + 1.2f, phase2 - 1.8f)
            val maxAmpPx = (amplitude * (canvasHeight * 0.42f)).coerceAtMost(centerY - 4f)
            val amplitudes = listOf(maxAmpPx * 0.7f, maxAmpPx * 0.5f, maxAmpPx * 1.0f, maxAmpPx * 0.8f)
            val strokeWidths = listOf(1.5.dp, 2.dp, 3.dp, 1.dp)

            for (w in 0 until 4) {
                val path = Path()
                path.moveTo(0f, centerY)

                var x = 0f
                while (x < width) {
                    val envelope = sin(PI * x / width).toFloat()
                    val y = centerY + sin(2.0 * PI * x / 280.0 + phases[w]).toFloat() * amplitudes[w] * envelope
                    path.lineTo(x, y)
                    x += 4f
                }
                path.lineTo(width, centerY)

                drawPath(
                    path = path,
                    color = colors[w],
                    style = Stroke(width = strokeWidths[w].toPx())
                )
            }
        } else {
            drawLine(
                color = Color(0x334FACFE),
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

@Composable
fun CircularControlButton(
    symbol: String,
    enabled: Boolean,
    size: Dp = 72.dp,
    onClick: () -> Unit
) {
    val buttonBg = if (enabled) DarkSurface else Color(0xFF141417)
    val borderBg = if (enabled) Color(0xFF2E2E38) else Color(0xFF1A1A1F)
    val textCol = if (enabled) Color.White else Color(0x33FFFFFF)

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(buttonBg)
            .border(1.dp, borderBg, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            fontSize = 28.sp,
            fontWeight = FontWeight.Light,
            color = textCol
        )
    }
}
