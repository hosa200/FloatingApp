package com.nusuk.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class NusukAccessibilityService : AccessibilityService() {

    companion object {
        var instance: NusukAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can optionally listen to events or handle on-demand from FloatingWidgetService
    }

    override fun onInterrupt() {}

    fun tapCoordinate(x: Float, y: Float, callback: (() -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 120)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback?.invoke()
            }
        }, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300, callback: (() -> Unit)? = null) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback?.invoke()
            }
        }, null)
    }

    fun hideSoftKeyboard() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                softKeyboardController.showMode = SHOW_MODE_HIDDEN
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun restoreSoftKeyboard() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                softKeyboardController.showMode = SHOW_MODE_AUTO
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isKeyboardShowing(): Boolean {
        return try {
            val wins = windows ?: return false
            wins.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) {
            false
        }
    }

    fun dismissKeyboard(): Boolean {
        if (isKeyboardShowing()) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return true
        }
        return false
    }

    fun focusInputBox(fallbackX: Float, fallbackY: Float) {
        val emptyArgs = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val selectAll = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 1000)
        }

        val nodes = findTextNodes()
        val target = nodes.find {
            it.isEditable || it.className?.toString()?.contains("EditText") == true
        } ?: nodes.find {
            val t = getNodeText(it)
            t.contains("لتفعيل مفتاح جديد") || t.contains("أدخل الرمز") || t.contains("أدخل رمز")
        }

        if (target != null) {
            try {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll)
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, emptyArgs)
            } catch (e: Exception) {}
        }

        // Always tap the physical center of the input box to open the keyboard
        tapCoordinate(fallbackX, fallbackY)
    }

    fun pasteFromKeyboardChip(code: String, screenW: Float, screenH: Float) {
        val emptyArgs = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val selectAll = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 1000)
        }

        // Clear any previous text in focused node before tapping the clipboard chip
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            try {
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll)
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, emptyArgs)
            } catch (e: Exception) {}
        }

        // 1. Check if the clipboard chip node is exposed in the IME window
        var chipClicked = false
        try {
            val imeNodes = mutableListOf<AccessibilityNodeInfo>()
            windows?.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.forEach { win ->
                win.root?.let { traverse(it, imeNodes) }
            }
            val chip = imeNodes.find {
                val t = getNodeText(it)
                t == code || t.contains(code) || (code.length >= 6 && t.contains(code.take(6))) || (t.startsWith("NG-") && t.length >= 5)
            }
            if (chip != null) {
                clickNode(chip)
                chipClicked = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Always tap the clipboard suggestion chip coordinate on the keyboard toolbar (x=50%, y=62.6%)
        if (!chipClicked) {
            tapCoordinate(screenW * 0.50f, screenH * 0.626f)
        }
    }

    fun findAllNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val roots = mutableListOf<AccessibilityNodeInfo>()

        try {
            rootInActiveWindow?.let { roots.add(it) }
        } catch (e: Exception) {}

        try {
            windows?.forEach { win ->
                try {
                    win.root?.let { roots.add(it) }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}

        for (r in roots) {
            traverse(r, result)
        }
        return result
    }

    fun findTextNodes(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        traverse(root, result)
        return result
    }

    private fun traverse(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()
        } else null
        val isInput = node.isEditable || node.className?.toString()?.contains("EditText") == true

        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || !hint.isNullOrBlank() || isInput) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            traverse(node.getChild(i), list)
        }
    }

    fun getNodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) return text
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank()) return desc
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString()?.trim()
            if (!hint.isNullOrBlank()) return hint
        }
        return ""
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            tapCoordinate(rect.exactCenterX(), rect.exactCenterY())
        }
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    break
                }
                parent = parent.parent
            }
        }
        return true
    }
}
