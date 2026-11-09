package com.nusuk.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
        val wins = windows ?: return false
        return wins.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    fun dismissKeyboard() {
        hideSoftKeyboard()
    }

    fun setInputText(code: String, fallbackX: Float, fallbackY: Float) {
        val root = rootInActiveWindow
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
        }
        val selectAllArgs = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 1000)
        }

        val nodes = findTextNodes()
        val target = nodes.find {
            it.isEditable ||
            it.className?.toString()?.contains("EditText") == true ||
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && (it.hintText?.toString()?.contains("رمز") == true || it.hintText?.toString()?.contains("أدخل") == true)) ||
            it.contentDescription?.toString()?.contains("رمز") == true ||
            it.contentDescription?.toString()?.contains("أدخل") == true ||
            it.text?.toString()?.contains("رمز") == true ||
            it.text?.toString()?.contains("أدخل") == true
        }

        var inputX = fallbackX
        var inputY = fallbackY

        if (target != null) {
            val rect = Rect()
            target.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                inputX = rect.exactCenterX()
                inputY = rect.exactCenterY()
            }
        }

        // Tap the input field directly to gain focus
        tapCoordinate(inputX, inputY)
        try { Thread.sleep(250) } catch (e: Exception) {}

        val inputNode = target ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (inputNode != null) {
            inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            // Unconditionally paste
            inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused != inputNode) {
            focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
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
