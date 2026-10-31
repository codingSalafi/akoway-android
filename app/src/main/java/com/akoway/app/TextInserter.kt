package com.akoway.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** Puts dictated text into whatever text box is focused in another app. */
object TextInserter {

    enum class Result { INSERTED, PASTED, COPIED_ONLY }

    fun insert(service: AccessibilityService, text: String): Result {
        val node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable) return copy(service, text)

        // Splice at the cursor (replacing any selection) and keep the cursor after the new text
        val current = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) { start = current.length; end = current.length }
        if (start > end) start = end.also { end = start }

        val before = current.substring(0, start)
        val piece = if (before.isNotEmpty() && !before.last().isWhitespace()) " $text" else text
        val updated = before + piece + current.substring(end)

        val setText = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) {
            val cursor = before.length + piece.length
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            })
            return Result.INSERTED
        }

        // Some fields (often web pages) refuse SET_TEXT but accept a paste
        clipboard(service).setPrimaryClip(ClipData.newPlainText("Akoway", piece))
        return if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) Result.PASTED else Result.COPIED_ONLY
    }

    private fun copy(context: Context, text: String): Result {
        clipboard(context).setPrimaryClip(ClipData.newPlainText("Akoway", text))
        return Result.COPIED_ONLY
    }

    private fun clipboard(context: Context) = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
}
