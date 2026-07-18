package com.hwanghj09.sonju.agent

object VisualTargetResolver {
    fun resolveScreenPoint(
        snapshot: UiSnapshot,
        normalizedX: Int?,
        normalizedY: Int?,
    ): Pair<Int, Int>? {
        val xValue = normalizedX?.takeIf { it in 0..1000 } ?: return null
        val yValue = normalizedY?.takeIf { it in 0..1000 } ?: return null
        if (snapshot.visualScreenWidth <= 0 || snapshot.visualScreenHeight <= 0) return null
        return Pair(
            xValue * snapshot.visualScreenWidth / 1000,
            yValue * snapshot.visualScreenHeight / 1000,
        )
    }

    fun resolveClickablePath(snapshot: UiSnapshot, normalizedX: Int?, normalizedY: Int?): String? {
        val (x, y) = resolveScreenPoint(snapshot, normalizedX, normalizedY) ?: return null
        val candidates = snapshot.elements.filter { element ->
            element.visible && element.enabled && element.clickable && !element.sensitive &&
                x in element.bounds.left..element.bounds.right &&
                y in element.bounds.top..element.bounds.bottom &&
                element.bounds.right > element.bounds.left &&
                element.bounds.bottom > element.bounds.top
        }
        if (candidates.isEmpty()) return null
        val smallestArea = candidates.minOf { element ->
            (element.bounds.right - element.bounds.left).toLong() *
                (element.bounds.bottom - element.bounds.top).toLong()
        }
        return candidates.filter { element ->
            (element.bounds.right - element.bounds.left).toLong() *
                (element.bounds.bottom - element.bounds.top).toLong() == smallestArea
        }.map(UiElement::path).distinct().singleOrNull()
    }

    fun fingerprintDistance(first: String?, second: String?): Int? {
        if (first == null || second == null || first.length != second.length) return null
        return runCatching {
            first.zip(second).sumOf { (left, right) ->
                Integer.bitCount(left.digitToInt(16) xor right.digitToInt(16))
            }
        }.getOrNull()
    }
}
