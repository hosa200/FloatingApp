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
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback?.invoke()
            }
        }, null)
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
        if (!text.isNullOrBlank()) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            traverse(node.getChild(i), list)
        }
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        tapCoordinate(rect.exactCenterX(), rect.exactCenterY())
        return true
    }
}
