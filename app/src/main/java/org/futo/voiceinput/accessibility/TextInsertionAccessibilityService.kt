package org.futo.voiceinput.accessibility

import android.accessibilityservice.AccessibilityService

import android.content.ClipData

import android.content.ClipboardManager

import android.content.Context

import android.os.Build

import android.os.Bundle

import android.os.Handler

import android.os.Looper
import android.os.SystemClock 

import android.util.Log

import android.view.accessibility.AccessibilityEvent

import android.view.accessibility.AccessibilityNodeInfo

import java.util.Locale

import java.util.concurrent.atomic.AtomicLong

class TextInsertionAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TISvc"
        private const val EXTRA_HINT_TEXT_KEY = "android.view.accessibility.extra.HINT_TEXT"
        private const val REASON_SUCCESS = "success"
        private const val DUPLICATE_WINDOW_MS = 1000L
        @Volatile
        private var active: TextInsertionAccessibilityService? = null
        @Volatile
        private var lastSuccessText: String? = null
        @Volatile
        private var lastSuccessAt: Long = 0L
        @Volatile
        private var successfullyInsertedTexts: MutableSet<String> = mutableSetOf()

        // Global duplicate prevention across all insertion methods
        @Volatile
        private var globalLastInsertedText: String? = null
        @Volatile
        private var globalLastInsertedAt: Long = 0L
        private const val GLOBAL_DUPLICATE_WINDOW_MS = 2000L

        @JvmStatic
        fun wasRecentlySuccessful(text: String, withinMs: Long = DUPLICATE_WINDOW_MS): Boolean {
            val snapshot = lastSuccessText ?: return false
            if (snapshot != text) return false
            val age = SystemClock.uptimeMillis() - lastSuccessAt
            return age in 0 until withinMs
        }

        fun clearRecentSuccess() {
            lastSuccessText = null
            lastSuccessAt = 0L
        }

        fun hasBeenSuccessfullyInserted(text: String): Boolean {
            return successfullyInsertedTexts.contains(text)
        }

        fun markTextAsSuccessfullyInserted(text: String) {
            successfullyInsertedTexts.add(text)
        }

        fun clearSuccessfullyInsertedTexts() {
            successfullyInsertedTexts.clear()
        }

        fun isGloballyDuplicate(text: String): Boolean {
            val snapshot = globalLastInsertedText ?: return false
            if (snapshot != text) return false
            val age = SystemClock.uptimeMillis() - globalLastInsertedAt
            return age < GLOBAL_DUPLICATE_WINDOW_MS
        }

        fun markGloballyInserted(text: String) {
            globalLastInsertedText = text
            globalLastInsertedAt = SystemClock.uptimeMillis()
        }

        private fun recordSuccess(text: String) {
            lastSuccessText = text
            lastSuccessAt = SystemClock.uptimeMillis()
        }

        fun requestInsert(text: CharSequence, delayMs: Long = 200L) {

            val service = active

            if (service == null) {
                Log.w(TAG, "requestInsert called but service not active")
                return
            }

            service.scheduleInsert(text.toString(), delayMs)

        }

        private fun getCallerInfo(): String = ""

        fun isActive(): Boolean = active != null

    }

    private data class ClipboardSnapshot(val hadClip: Boolean, val clip: ClipData?)

    private fun captureClipboard(clipboard: ClipboardManager): ClipboardSnapshot =

        ClipboardSnapshot(clipboard.hasPrimaryClip(), clipboard.primaryClip)

    private fun clearClipboard(clipboard: ClipboardManager) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

            clipboard.clearPrimaryClip()

        } else {

            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))

        }

    }

    private fun restoreClipboard(clipboard: ClipboardManager, snapshot: ClipboardSnapshot) {

        if (snapshot.hadClip) {

            snapshot.clip?.let { clipboard.setPrimaryClip(it) } ?: clearClipboard(clipboard)

        } else {

            clearClipboard(clipboard)

        }

    }

    private val handler = Handler(Looper.getMainLooper())

    private var pendingInsertRunnable: Runnable? = null

    private val nextRequestId = AtomicLong(0L)

    private var activeRequest: InsertRequest? = null

    private data class InsertRequest(

        val id: Long,

        val text: String,

        var targetPackage: String? = null,

        var targetViewId: String? = null,

        var targetWindowId: Int? = null

    ) {

        fun hasTarget(): Boolean = targetPackage != null || targetViewId != null || targetWindowId != null

        fun captureTarget(node: AccessibilityNodeInfo) {

            targetPackage = node.packageName?.toString()

            targetViewId = node.viewIdResourceName

            targetWindowId = try {

                node.windowId

            } catch (_: Throwable) {

                null

            }

        }

        fun matches(node: AccessibilityNodeInfo): Boolean {

            targetPackage?.let {

                if (node.packageName?.toString() != it) return false

            }

            targetViewId?.let {

                if (node.viewIdResourceName != it) return false

            }

            targetWindowId?.let {

                val candidate = try {

                    node.windowId

                } catch (_: Throwable) {

                    null

                }

                if (candidate != it) return false

            }

            return true

        }

    }

    private fun postInsertRunnable(request: InsertRequest, attemptsLeft: Int, delayMs: Long) {

        val runnable = Runnable { tryInsertWithRetries(request, attemptsLeft) }

        pendingInsertRunnable = runnable

        handler.postDelayed(runnable, delayMs)

    }

    private fun clearPendingRunnable() {

        pendingInsertRunnable?.let { handler.removeCallbacks(it) }

        pendingInsertRunnable = null

    }

    private fun completeRequest(request: InsertRequest?, reason: String) {

        if (request == null) {

            clearPendingRunnable()

            return

        }

        if (activeRequest !== request) {

            Log.d(TAG, "[FUTO_DEBUG] completeRequest ignore id=${request.id} reason=$reason (not active, current active=${activeRequest?.id})")

            return

        }

        Log.d(TAG, "[FUTO_DEBUG] COMPLETING REQUEST ${request.id} reason=$reason text='${request.text}'")

        if (reason == REASON_SUCCESS) {
            recordSuccess(request.text)
        }

        clearPendingRunnable()

        activeRequest = null

    }

    override fun onServiceConnected() {

        super.onServiceConnected()

        active = this
        clearRecentSuccess()

        Log.d(TAG, "service connected")

    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {

        Log.d(TAG, "service unbind start")
        
        if (active === this) active = null

        completeRequest(activeRequest, "service unbind")

        Log.d(TAG, "service unbind complete")

        return super.onUnbind(intent)

    }

    override fun onDestroy() {

        Log.d(TAG, "service destroy start")

        if (active === this) active = null

        completeRequest(activeRequest, "service destroy")

        Log.d(TAG, "service destroy complete")

        super.onDestroy()

    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        // No-op. We trigger on demand.

    }

    override fun onInterrupt() { }

    private fun scheduleInsert(text: String, delayMs: Long) {

        Log.d(TAG, "scheduleInsert len=${text.length} delay=${delayMs}")

        if (activeRequest != null) {
            Log.d(TAG, "cancelling active request ${activeRequest?.id} for new request")
        }

        completeRequest(activeRequest, "superseded by new request")

        val request = InsertRequest(id = nextRequestId.incrementAndGet(), text = text)

        activeRequest = request

        // Clear recent success and successfully inserted texts to avoid preventing new insertions
        clearRecentSuccess()
        clearSuccessfullyInsertedTexts()

        Log.d(TAG, "Created insertion request ${request.id} with ${delayMs}ms delay")

        // Note: Don't clear global tracking here as it should persist across requests
        // to prevent cross-method duplicates

        postInsertRunnable(request, 24, delayMs)

    }

    private fun tryInsertWithRetries(request: InsertRequest, attemptsLeft: Int) {

        Log.d(TAG, "tryInsertWithRetries: request ${request.id}, attemptsLeft=$attemptsLeft")

        if (activeRequest !== request) {

            Log.d(TAG, "request ${request.id} superseded by ${activeRequest?.id}; aborting")

            return

        }

        // Minimal duplicate prevention: skip if we've already inserted exactly this text recently in this session
        if (hasBeenSuccessfullyInserted(request.text)) {
            Log.d(TAG, "request ${request.id} text has already been inserted; skipping to prevent duplicates")
            completeRequest(request, "already inserted")
            return
        }

        Log.d(TAG, "Proceeding with insertion for request ${request.id}, text length: ${request.text.length}")

        Log.d(TAG, "tryInsertWithRetries: request ${request.id}, attemptsLeft: $attemptsLeft")

        if (attemptsLeft <= 0) {

            Log.w(TAG, "abort insert after retries for request ${request.id}")

            completeRequest(request, "retry limit reached")

            return

        }

        Log.d(TAG, "tryInsert id=${request.id} remaining=$attemptsLeft textLength=${request.text.length}")

        if (isOwnWindowActive()) {

            Log.d(TAG, "own window active; delaying")

            clearPendingRunnable()

            postInsertRunnable(request, attemptsLeft - 1, 200)

            return

        }

        val node = findFocusedEditable() ?: run {

            Log.d(TAG, "no focused editable; retrying")

            clearPendingRunnable()

            postInsertRunnable(request, attemptsLeft - 1, 200)

            return

        }

        if (!request.hasTarget()) {

            request.captureTarget(node)

            Log.d(TAG, "captured target id=${request.id} pkg=${request.targetPackage} view=${request.targetViewId} window=${request.targetWindowId}")

        } else if (!request.matches(node)) {

            // Additional check: if the user has switched to a different app entirely, cancel immediately
            val currentPackage = node.packageName?.toString() ?: ""
            val isDifferentApp = request.targetPackage != null && request.targetPackage != currentPackage

            Log.d(TAG, "focus changed before confirmation; cancelling request ${request.id}")

            completeRequest(request, "focus changed to different app")

            return

        }

        val focused = ensureFocused(node)

        Log.d(TAG, "focused=$focused node=$node")

        moveCursorToEnd(node)

        when (val outcome = performSetText(request.text, node)) {

            SetTextOutcome.Success -> {

                Log.d(TAG, "setText ok and verified")

                // Mark this text as successfully inserted to prevent future duplicates
                markTextAsSuccessfullyInserted(request.text)

                // Mark globally to prevent any other insertion method from inserting the same text
                markGloballyInserted(request.text)

                // Clear recent success immediately to prevent any race conditions
                clearRecentSuccess()

                completeRequest(request, "success")

                return

            }

            SetTextOutcome.AppliedNeedsRetry -> {

                Log.d(TAG, "setText applied but verification pending; retrying")

                clearPendingRunnable()

                postInsertRunnable(request, attemptsLeft - 1, 200)

                return

            }

            SetTextOutcome.NotSupported, SetTextOutcome.Failed -> {

                Log.w(TAG, "setText outcome=$outcome; abandoning request ${request.id}")

                completeRequest(request, "setText=$outcome")

                return

            }

        }

        Log.w(TAG, "setText returned unexpected outcome; abandoning request ${request.id}")

        completeRequest(request, "unexpected outcome")

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

    private fun performSetText(text: String, node: AccessibilityNodeInfo): SetTextOutcome {

        val supportsSetText = node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id }

        if (!supportsSetText) {

            Log.d(TAG, "performSetText: action not supported for node=$node")

            return SetTextOutcome.NotSupported

        }

        val beforeRaw = (node.text ?: "").toString()
        val effectiveBefore = sanitizeExistingText(beforeRaw, node)

        if (text.isEmpty()) {

            return SetTextOutcome.Success

        }

        val trimmedBefore = effectiveBefore.trim()

        val shouldReplace = trimmedBefore.isEmpty()

        if (!shouldReplace && effectiveBefore.endsWith(text)) {

            return SetTextOutcome.Success

        }

        val newText = if (shouldReplace) {

            text

        } else {

            combineText(effectiveBefore, text)

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

    private fun sanitizeExistingText(raw: String, node: AccessibilityNodeInfo): String {
        val trimmedRaw = raw.trim()
        if (trimmedRaw.isEmpty()) return ""
        if (looksLikePlaceholder(trimmedRaw)) return ""
        val hintText = extractHintText(node)
        val normalizedExisting = normalizePlaceholderForComparison(trimmedRaw)
        val normalizedHint = normalizePlaceholderForComparison(hintText)
        if (normalizedExisting != null && normalizedHint != null && normalizedExisting == normalizedHint && cursorLikelyAtStart(node)) {
            return ""
        }
        return raw
    }

    private fun extractHintText(node: AccessibilityNodeInfo): CharSequence? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val directHint = node.hintText
            if (!directHint.isNullOrBlank()) {
                return directHint
            }
        }
        val extras = node.extras
        val compatHint = extras?.getCharSequence(EXTRA_HINT_TEXT_KEY)
        return if (compatHint.isNullOrBlank()) null else compatHint
    }

    private fun cursorLikelyAtStart(node: AccessibilityNodeInfo): Boolean {
        return try {
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            start <= 0 && end <= 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun normalizePlaceholderForComparison(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        return text.toString()
            .replace("\u00A0", " ")
            .replace("\u202F", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', '\u2026', ':')
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun looksLikePlaceholder(text: CharSequence?): Boolean {
        val normalized = normalizePlaceholderForComparison(text) ?: return false
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




