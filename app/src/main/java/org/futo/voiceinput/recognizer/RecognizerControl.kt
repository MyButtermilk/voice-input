package org.futo.voiceinput.recognizer

import org.futo.voiceinput.MagnitudeState
import org.futo.voiceinput.ml.RunState

interface RecognizerControl {
    fun isCurrentlyRecording(): Boolean
    fun finishRecognizerIfRecording()
    fun reset()
    fun create()
    fun cancelRecognizer()
    fun permissionResultGranted()
    fun permissionResultRejected()
    fun pauseVAD(v: Boolean)
    fun forceLanguage(lang: String?)
}

interface RecognizerUiCallbacks {
    fun onCancelled()
    fun onFinished(result: String)
    fun onLanguageDetected(result: String)
    fun onPartialResult(result: String)
    fun onDecodingStatus(status: RunState)
    fun onLoading()
    fun onNeedPermission()
    fun onPermissionRejected()
    fun onRecordingStarted()
    fun onUpdateMagnitude(magnitude: Float, state: MagnitudeState)
    fun onProcessing()
    fun onRealtimeFinalResult(result: String) {}
    fun onRealtimeError(message: String) {}
}

