package com.hwanghj09.sonju.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Xml
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File

/** Shell-only, debug-only observation for QA. Never reconnects or suppresses accessibility. */
class DebugSnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val output = File(context.cacheDir, "command-qa-snapshot.xml")
        output.delete()
        val service = SonjuAccessibilityService.instance ?: return
        val root = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }.mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() != context.packageName } ?: return
        val snapshot = UiTreeReader.snapshot(root, SystemClock.elapsedRealtime(), service.currentDisplayBounds())
        output.writer().use { writer ->
            val xml = Xml.newSerializer().apply { setOutput(writer); startDocument("UTF-8", true) }
            xml.startTag(null, "hierarchy")
            xml.attribute(null, "observed-at", SystemClock.elapsedRealtime().toString())
            xml.attribute(null, "window-id", snapshot.windowId.toString())
            xml.attribute(null, "display-bounds", snapshot.windowBounds.toString())
            xml.attribute(null, "resource-display-size", "${service.resources.displayMetrics.widthPixels}x${service.resources.displayMetrics.heightPixels}")
            xml.attribute(null, "tree-truncated", snapshot.treeTruncated.toString())
            xml.attribute(null, "rendered-content-unavailable",
                com.hwanghj09.sonju.agent.ScreenContextHandoff.hasUnobservedRenderedContent(snapshot).toString())
            snapshot.elements.filter { it.visible && !it.sensitive }.forEach { node ->
                xml.startTag(null, "node")
                mapOf("package" to snapshot.packageName, "text" to node.text.orEmpty(),
                    "content-desc" to node.contentDescription.orEmpty(), "resource-id" to node.viewId.orEmpty(),
                    "class" to node.className, "checkable" to node.checkable.toString(),
                    "checked" to node.checked.toString(), "enabled" to node.enabled.toString(),
                    "editable" to node.editable.toString(), "focused" to node.focused.toString(),
                    "hint" to node.hintText.orEmpty(), "actions" to node.availableActions.joinToString(","),
                    "clickable" to node.clickable.toString(), "path" to node.path,
                    "bounds" to "[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]")
                    .forEach { (key, value) -> xml.attribute(null, key, value) }
                xml.endTag(null, "node")
            }
            xml.endTag(null, "hierarchy")
            xml.endDocument()
        }
    }
}
