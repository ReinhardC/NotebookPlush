package com.notebookplush.ui

import android.os.Build
import android.view.Window
import android.view.WindowInsetsController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Easynews's window-scoped full screen: edge swipes reveal bars, disposal restores them. */
@Composable
internal fun ImmersiveSystemBars(window: Window) {
    val view = LocalView.current
    if (Build.VERSION.SDK_INT >= 35) {
        DisposableEffect(window) {
            // DeX's grey caption belongs to the activity window, even when a dialog has focus.
            val controller = window.insetsController
            val transparentCaption = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
            val previousCaption = (controller?.systemBarsAppearance ?: 0) and transparentCaption
            controller?.setSystemBarsAppearance(transparentCaption, transparentCaption)
            onDispose { controller?.setSystemBarsAppearance(previousCaption, transparentCaption) }
        }
    }
    DisposableEffect(window, view) {
        val controller = WindowInsetsControllerCompat(window, view)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
}
