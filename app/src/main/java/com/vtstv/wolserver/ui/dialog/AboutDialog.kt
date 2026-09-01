/**
 * Copyright (c) 2025-2026 Murr
 * https://github.com/vtstv/wolserver
 */
package com.vtstv.wolserver.ui.dialog

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageView
import com.vtstv.wolserver.R

/**
 * Animated About dialog for Simple WOL Server.
 */
object AboutDialog {

    fun show(activity: Activity) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_about)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.65).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val logo = dialog.findViewById<ImageView>(R.id.aboutAppIcon)
        val glowRing = dialog.findViewById<View>(R.id.aboutGlowRing)
        val btnClose = dialog.findViewById<Button>(R.id.btnAboutClose)

        // Floating Levitation Animation
        val floatAnim = ObjectAnimator.ofFloat(logo, "translationY", 0f, -12f, 0f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        floatAnim.start()

        // Pulsing Scale Animation for Glow Ring
        val scaleXAnim = ObjectAnimator.ofFloat(glowRing, "scaleX", 1.0f, 1.20f).apply {
            duration = 1400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        val scaleYAnim = ObjectAnimator.ofFloat(glowRing, "scaleY", 1.0f, 1.20f).apply {
            duration = 1400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        scaleXAnim.start()
        scaleYAnim.start()

        // Continuous Rotation for Glow Ring
        val rotateAnim = ObjectAnimator.ofFloat(glowRing, "rotation", 0f, 360f).apply {
            duration = 8000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        rotateAnim.start()

        dialog.setOnDismissListener {
            floatAnim.cancel()
            scaleXAnim.cancel()
            scaleYAnim.cancel()
            rotateAnim.cancel()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
