package com.kate.assistant.services

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kate.assistant.bridge.KateEvent
import com.kate.assistant.bridge.KateEventBus

class KateAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KateAccessibility"

        // Volatile so the service instance is visible across threads safely
        @Volatile var instance: KateAccessibilityService? = null
    }

    override fun onServiceConnected() {
        // ── FIX: DO NOT override serviceInfo here ──────────────────
        // Android already loaded all capabilities from accessibility_config.xml.
        // Overwriting serviceInfo in code (especially with a new blank object)
        // causes Android to report "This service is malfunctioning" on many OEMs.
        // The XML config already declares all needed flags, event types, and
        // canRetrieveWindowContent. Trust the XML. ──────────────────
        instance = this
        Log.d(TAG, "✅ Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val pkg = event.packageName?.toString() ?: return
                    if (pkg != packageName) {
                        KateEventBus.emit(KateEvent.AppOpened(pkg))
                    }
                }
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    val text = event.text.joinToString(" ").trim()
                    if (text.isNotBlank()) Log.d(TAG, "Notification: $text")
                }
                else -> Unit
            }
        } catch (e: Exception) {
            // Never crash inside onAccessibilityEvent — it can kill the whole service
            Log.e(TAG, "Event error: ${e.message}")
        }
    }

    // ── Ghost typing ──────────────────────────────────────────
    fun ghostType(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: run {
                Log.e(TAG, "No active window for ghostType"); return false
            }

            var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditableNode(root)

            if (target == null) {
                Log.e(TAG, "No editable field found"); return false
            }

            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(150)

            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            if (result) {
                Thread.sleep(200)
                val sendBtn = findNodeByText(root, listOf("send", "Send", "submit", "Submit"))
                sendBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }

            Log.d(TAG, "Ghost type result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "ghostType error: ${e.message}")
            false
        }
    }

    // ── Screen reader ─────────────────────────────────────────
    fun readScreen(): String = try {
        extractText(rootInActiveWindow).trim()
    } catch (e: Exception) {
        Log.e(TAG, "readScreen error: ${e.message}"); ""
    }

    // ── Navigation ────────────────────────────────────────────
    fun goBack(): Boolean           = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean           = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean      = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun takeScreenshot(): Boolean   = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    fun showNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

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

    private fun findNodeByText(
        node: AccessibilityNodeInfo?,
        targets: List<String>
    ): AccessibilityNodeInfo? {
        node ?: return null
        val cd   = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        if (targets.any { cd.contains(it, true) || text.contains(it, true) }) return node
        for (i in 0 until node.childCount) {
            findNodeByText(node.getChild(i), targets)?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }
}
