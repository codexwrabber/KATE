package com.kate.assistant.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kate.assistant.bridge.KateEvent
import com.kate.assistant.bridge.KateEventBus

class KateAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KateAccessibility"

        // Static reference so KateService can call ghost typing
        var instance: KateAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType    = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
        }
        serviceInfo = info
        Log.d(TAG, "Kate Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {

            // Screen content changed — tell Kate what's on screen
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg      = event.packageName?.toString() ?: return
                val activity = event.className?.toString() ?: ""
                Log.d(TAG, "Window changed: $pkg / $activity")
                KateEventBus.emit(KateEvent.AppOpened(pkg))
            }

            // Notification received — read it aloud if important
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text.joinToString(" ").trim()
                if (text.isNotBlank()) {
                    Log.d(TAG, "Notification: $text")
                    KateEventBus.emit(KateEvent.Error("Notification: $text"))
                }
            }

            else -> Unit
        }
    }

    // ── Ghost typing — type into any focused field ───────────
    fun ghostType(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "Ghost type '$text': $result")
        return result
    }

    // ── Click element by content description ─────────────────
    fun tapElement(description: String): Boolean {
        val node = findNodeByDescription(rootInActiveWindow, description)
            ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ── Scroll down ──────────────────────────────────────────
    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    // ── Read everything on screen ────────────────────────────
    fun readScreen(): String {
        return extractText(rootInActiveWindow)
    }

    // ── Go back ──────────────────────────────────────────────
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    // ── Go home ──────────────────────────────────────────────
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    // ── Open recents ─────────────────────────────────────────
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // ── Take screenshot ──────────────────────────────────────
    fun takeScreenshot() = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    // ── Show notifications ───────────────────────────────────
    fun showNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ── Helpers ──────────────────────────────────────────────
    private fun extractText(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        val sb = StringBuilder()
        if (!node.text.isNullOrBlank()) sb.append(node.text).append(" ")
        if (!node.contentDescription.isNullOrBlank())
            sb.append(node.contentDescription).append(" ")
        for (i in 0 until node.childCount)
            sb.append(extractText(node.getChild(i)))
        return sb.toString().trim()
    }

    private fun findNodeByDescription(
        node: AccessibilityNodeInfo?,
        desc: String
    ): AccessibilityNodeInfo? {
        node ?: return null
        if (node.contentDescription?.contains(desc, true) == true ||
            node.text?.contains(desc, true) == true) return node
        for (i in 0 until node.childCount) {
            findNodeByDescription(node.getChild(i), desc)?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
