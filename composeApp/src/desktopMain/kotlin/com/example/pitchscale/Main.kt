package com.example.pitchscale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.BasicStroke
import java.awt.Desktop
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.desktop.AppReopenedListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.sin

private fun renderMenuBarIcon(active: Boolean): BufferedImage {
    val w = 44
    val h = 36
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    g.color = if (active) java.awt.Color(0, 242, 254) else java.awt.Color.WHITE
    g.stroke = BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    val cy = h / 2.0
    val path = Path2D.Double()
    path.moveTo(4.0, cy)
    val steps = 32
    for (i in 0..steps) {
        val t = i.toDouble() / steps
        val x = 4.0 + (w - 8.0) * t
        val env = sin(PI * t)
        val amp = if (active) h * 0.40 else h * 0.30
        val y = cy + sin(4.0 * PI * t) * amp * env
        path.lineTo(x, y)
    }
    g.draw(path)
    g.dispose()
    return img
}

fun main() {
    System.setProperty("java.awt.headless", "false")
    System.setProperty("apple.awt.application.name", "Pitch Scaler")
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")

    application {
        val controller = remember { MacosPitchController() }
        val isRunning by controller.isRunning

        var isPopoverOpen by remember { mutableStateOf(true) }
        var lastToggleMillis by remember { mutableStateOf(System.currentTimeMillis()) }
        val popoverWidth = 340
        val popoverHeight = 520

        val screenSize = remember { Toolkit.getDefaultToolkit().screenSize }
        val windowState = rememberWindowState(
            width = popoverWidth.dp,
            height = popoverHeight.dp,
            position = WindowPosition((screenSize.width - popoverWidth - 16).dp, 32.dp)
        )

        val trayIconRef = remember { mutableStateOf<TrayIcon?>(null) }

        // Register Dock click reopen + AWT SystemTray icon on the AWT Event Dispatch Thread (EDT)
        DisposableEffect(Unit) {
            runCatching {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().addAppEventListener(AppReopenedListener {
                        lastToggleMillis = System.currentTimeMillis()
                        isPopoverOpen = !isPopoverOpen
                    })
                }
            }

            SwingUtilities.invokeLater {
                if (SystemTray.isSupported()) {
                    runCatching {
                        val icon = TrayIcon(renderMenuBarIcon(active = isRunning), "Pitch Scaler").apply {
                            isImageAutoSize = true
                            addMouseListener(object : MouseAdapter() {
                                override fun mousePressed(e: MouseEvent) {
                                    lastToggleMillis = System.currentTimeMillis()
                                    val anchorX = e.xOnScreen
                                    if (anchorX > 0) {
                                        val clampedX = (anchorX - popoverWidth / 2)
                                            .coerceIn(12, screenSize.width - popoverWidth - 12)
                                        windowState.position = WindowPosition(clampedX.dp, 32.dp)
                                    }
                                    isPopoverOpen = !isPopoverOpen
                                }
                            })
                        }
                        SystemTray.getSystemTray().add(icon)
                        trayIconRef.value = icon
                    }
                }
            }

            onDispose {
                val icon = trayIconRef.value
                if (icon != null) {
                    SwingUtilities.invokeLater {
                        runCatching { SystemTray.getSystemTray().remove(icon) }
                    }
                }
            }
        }

        LaunchedEffect(isRunning) {
            val icon = trayIconRef.value
            if (icon != null) {
                SwingUtilities.invokeLater {
                    icon.image = renderMenuBarIcon(active = isRunning)
                }
            }
        }

        Window(
            onCloseRequest = { isPopoverOpen = false },
            visible = isPopoverOpen,
            title = "Pitch Scaler",
            undecorated = true,
            transparent = false,
            resizable = false,
            alwaysOnTop = true,
            state = windowState
        ) {
            LaunchedEffect(window) {
                window.addWindowFocusListener(object : WindowAdapter() {
                    override fun windowLostFocus(e: WindowEvent?) {
                        if (System.currentTimeMillis() - lastToggleMillis > 300L) {
                            isPopoverOpen = false
                        }
                    }
                })
            }

            LaunchedEffect(isPopoverOpen) {
                if (isPopoverOpen) {
                    window.toFront()
                    window.requestFocus()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkBg)
                    .border(1.dp, Color(0xFF2E2E38))
            ) {
                PitchScalerApp(controller = controller)

                Text(
                    text = "Quit",
                    color = Color(0x77FFFFFF),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 14.dp, end = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            controller.stopEngine()
                            exitApplication()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
