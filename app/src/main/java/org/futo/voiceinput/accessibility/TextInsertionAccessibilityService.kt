package org.futo.voiceinput.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class TextInsertionAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "TISvc"
        @Volatile
        private var active: TextInsertionAccessibilityService? = null

        fun requestInsert(text: CharSequence, delayMs: Long = 200L) {
            val service = active
            if (service == null) {
                Log.w(TAG, "requestInsert called but service not active")
                return
            }
            Log.d(TAG, "requestInsert len=${text.length} delay=$delayMs")
            service.scheduleInsert(text.toString(), delayMs)
        }

        fun isActive(): Boolean = active != null
    }

    private val handler = Handler(Looper.getMainLooper())
    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        Log.d(TAG, "service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (active === this) active = null
        Log.d(TAG, "service unbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (active === this) active = null
        Log.d(TAG, "service destroy")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op. We trigger on demand.
    }

    override fun onInterrupt() { }

    private fun scheduleInsert(text: String, delayMs: Long) {
        Log.d(TAG, "scheduleInsert len=${text.length} delay=${delayMs}")
        handler.postDelayed({
            tryInsertWithRetries(text, 24)
        }, delayMs)
    }

    private fun tryInsertWithRetries(text: String, attemptsLeft: Int) {
        if (attemptsLeft <= 0) {
            Log.w(TAG, "abort insert after retries")
            return
        }
        Log.d(TAG, "tryInsert remaining=$attemptsLeft textLength=${text.length}")
        if (isOwnWindowActive()) {
            Log.d(TAG, "own window active; delaying")
            handler.postDelayed({ tryInsertWithRetries(text, attemptsLeft - 1) }, 200)
            return
        }

        val node = findFocusedEditable() ?: run {
            Log.d(TAG, "no focused editable; retrying")
            handler.postDelayed({ tryInsertWithRetries(text, attemptsLeft - 1) }, 200)
            return
        }

        val focused = ensureFocused(node)
        Log.d(TAG, "focused=$focused node=$node")
        moveCursorToEnd(node)

        if (performPaste(text, node)) {
            Log.d(TAG, "paste ok")
            return
        }

        when (performSetText(text, node)) {
            SetTextOutcome.Success -> {
                Log.d(TAG, "setText ok and verified")
                return
            }
            SetTextOutcome.AppliedNeedsRetry -> {
                Log.d(TAG, "setText applied but verification pending; retrying")
                handler.postDelayed({ tryInsertWithRetries(text, attemptsLeft - 1) }, 200)
                return
            }
            SetTextOutcome.NotSupported -> {
                Log.d(TAG, "setText not supported on node; retrying")
            }
            SetTextOutcome.Failed -> {
                Log.w(TAG, "setText action failed; retrying")
            }
        }

        handler.postDelayed({ tryInsertWithRetries(text, attemptsLeft - 1) }, 200)
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "findFocusedEditable: root null")
            return null
        }
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            Log.d(TAG, "findFocusedEditable: using focused=$focused")
            return focused
        }
        val fallback = findFirstEditable(root)
        if (fallback == null) {
            Log.d(TAG, "findFocusedEditable: no editable node")
        }
        return fallback
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val res = findFirstEditable(node.getChild(i))
            if (res != null) return res
        }
        return null
    }

    private fun performPaste(text: String, node: AccessibilityNodeInfo): Boolean {
        if (!node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.id }) {
            Log.d(TAG, "performPaste: action not supported")
            return false
        }
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("voice input", text))
            val result = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.id)
            Log.d(TAG, "performPaste result=$result")
            result
        } catch (t: Throwable) {
            Log.w(TAG, "performPaste failed: ${t.message}")
            false
        }
    }

    private fun performSetText(text: String, node: AccessibilityNodeInfo): SetTextOutcome {
        val supportsSetText = node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id }
        if (!supportsSetText) {
            Log.d(TAG, "performSetText: action not supported for node=$node")
            return SetTextOutcome.NotSupported
        }

        val beforeRaw = (node.text ?: "").toString()

        if (text.isEmpty()) {
            return SetTextOutcome.Success
        }

        val trimmedBefore = beforeRaw.trim()
        val shouldReplace = trimmedBefore.isEmpty() || looksLikePlaceholder(trimmedBefore)
        if (!shouldReplace && beforeRaw.endsWith(text)) {
            return SetTextOutcome.Success
        }

        val newText = if (shouldReplace) {
            text
        } else {
            combineText(beforeRaw, text)
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val actionResult = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id, args)
        if (!actionResult) {
            Log.w(TAG, "performSetText failed to perform action")
            return SetTextOutcome.Failed
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            node.refresh()
        }
        val afterRaw = (node.text ?: "").toString()
        return if (afterRaw == newText) {
            SetTextOutcome.Success
        } else {
            SetTextOutcome.AppliedNeedsRetry
        }
    }





    private enum class SetTextOutcome {
        NotSupported,
        Failed,
        Success,
        AppliedNeedsRetry
    }

    private fun ensureFocused(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (!node.isFocused) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Log.d(TAG, "ensureFocused requested focus result=$result")
                result
            } else {
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ensureFocused failed: ${t.message}")
            false
        }
    }

    private fun moveCursorToEnd(node: AccessibilityNodeInfo) {
        try {
            val textLength = (node.text ?: "").length
            val args = Bundle()
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, textLength)
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (_: Throwable) { }
    }

    private fun isOwnWindowActive(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val pkg = root.packageName?.toString() ?: return false
            pkg.startsWith("org.futo.voiceinput")
        } catch (_: Throwable) { false }
    }

    private fun combineText(existing: String, addition: String): String {
        if (existing.isEmpty()) return addition
        val needsSpace = !existing.last().isWhitespace() && addition.isNotEmpty() && !addition.first().isWhitespace()
        return if (needsSpace) existing + " " + addition else existing + addition
    }

    private fun looksLikePlaceholder(text: CharSequence?): Boolean {
        if (text.isNullOrBlank()) return false
        val normalized = text.toString()
            .replace("\u00A0", " ")
            .replace("\u202F", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', '\u2026', ':')
            .trim()
            .lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        val placeholders = setOf(
            "nachricht",
            "nachricht verfassen",
            "nachricht schreiben",
            "message",
            "type a message",
            "write a message",
            "schreibe eine nachricht"
        )
        return normalized in placeholders
    }





















}




