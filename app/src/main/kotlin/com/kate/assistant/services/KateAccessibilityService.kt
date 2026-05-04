package com.kate.assistant.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kate.assistant.bridge.KateEvent
import com.kate.assistant.bridge.KateEventBus

class KateAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KateAccessibility"
        var instance: KateAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags               = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                                  AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                                  AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
        Log.d(TAG, "✅ Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg != packageName) {
                    KateEventBus.emit(KateEvent.AppOpened(pkg))
                }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text.joinToString(" ").trim()
                if (text.isNotBlank()) {
                    Log.d(TAG, "Notification: $text")
                }
            }
            else -> Unit
        }
    }

    // ── Ghost typing ─────────────────────────────────────────
    fun ghostType(text: String): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.e(TAG, "No active window"); return false
        }

        // Try focused input first
        var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        // Fallback — find any editable field
        if (target == null) target = findEditableNode(root)

        if (target == null) {
            Log.e(TAG, "No editable field"); return false
        }

        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(150)

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        val result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (result) {
            Thread.sleep(200)
            val sendBtn = findNodeByDescription(root, "send")
                ?: findNodeByDescription(root, "Send")
                ?: findNodeByDescription(root, "submit")
            sendBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        Log.d(TAG, "Ghost type: $result")
        return result
    }

    // ── Read screen ───────────────────────────────────────────
    fun readScreen(): String = extractText(rootInActiveWindow).trim()

    // ── Navigation ────────────────────────────────────────────
    fun goBack(): Boolean        = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean        = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean   = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun takeScreenshot(): Boolean = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    fun showNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ── Helpers ───────────────────────────────────────────────
    private fun extractText(node: AccessibilityNodeInfo?): String {
        node ?: return ""
        val sb = StringBuilder()
        if (!node.text.isNullOrBlank()) sb.append(node.text).append(" ")
        if (!node.contentDescription.isNullOrBlank()) sb.append(node.contentDescription).append(" ")
        for (i in 0 until node.childCount) sb.append(extractText(node.getChild(i)))
        return sb.toString()
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditableNode(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun findNodeByDescription(
        node: AccessibilityNodeInfo?, desc: String
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
        Log.d(TAG, "Interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
