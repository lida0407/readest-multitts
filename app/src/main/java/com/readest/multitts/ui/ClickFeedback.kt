package com.readest.multitts.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.HapticFeedbackConstants

/**
 * Press feedback for the app's custom controls.
 *
 * Most of the chips and rows here are plain TextViews, which give no sign they were
 * hit. This adds the two cues a tap is expected to produce: a quick shrink under the
 * finger and a light haptic tick.
 */
object ClickFeedback {

    private const val PRESSED_SCALE = 0.94f
    private const val DOWN_MS = 70L
    private const val UP_MS = 130L

    /** Apply to one view. Safe on views that already have a click listener. */
    @SuppressLint("ClickableViewAccessibility")
    fun apply(view: View) {
        if (view.getTag(TAG_KEY) == true) return
        view.setTag(TAG_KEY, true)

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                        .setDuration(DOWN_MS).start()
                    v.performHapticFeedback(
                        HapticFeedbackConstants.VIRTUAL_KEY,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(UP_MS).start()
                }
            }
            false // never consume: the view's own click handling still runs
        }
    }

    /** Apply to every clickable view in a hierarchy, including nested ones. */
    fun applyToTree(root: View) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyToTree(root.getChildAt(i))
        }
        if (root.isClickable) apply(root)
    }

    private const val TAG_KEY = -0x7ffffff0
}
