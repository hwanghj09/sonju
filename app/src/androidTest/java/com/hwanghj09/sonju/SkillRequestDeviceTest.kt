package com.hwanghj09.sonju

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hwanghj09.sonju.skill.SkillRequestPattern
import com.hwanghj09.sonju.task.TaskParameter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android ICU and the desktop JVM do not accept exactly the same regular expressions. */
@RunWith(AndroidJUnit4::class)
class SkillRequestDeviceTest {
    @Test fun requestPatternsInitializeAndBindOnAndroid() {
        val pattern = requireNotNull(SkillRequestPattern.capture("메모에 여행 준비라고 적어줘",
            mapOf("input1" to TaskParameter("input1", "여행 준비"))))
        assertEquals("오후 일정", pattern.bind("메모에 오후 일정라고 적어 주세요")?.get("input1")?.value)
        assertNull(pattern.bind("메모에 오후 일정라고 적지 말아줘"))
        assertNull(pattern.bind("메모에 ${'$'}{input1}라고 적어줘"))
        assertNull(pattern.bind("메모에 가을 일정이라고 적어줘"))
    }

    @Test fun resultPatternsUseCurrentValuesAndExactLiteralEvidenceOnAndroid() {
        val parameters = mapOf("input1" to TaskParameter("input1", "여행 준비"))
        val pattern = requireNotNull(SkillRequestPattern.captureText("저장된 기록: 여행 준비", parameters))
        val changed = mapOf("input1" to TaskParameter("input1", "회의 자료"))
        assertTrue(pattern.matchesText("저장된 기록: 회의 자료", changed))
        assertFalse(pattern.matchesText("저장된 기록: 여행 준비", changed))
        assertFalse(pattern.matchesText("저장 실패: 회의 자료", changed))
        assertNull(SkillRequestPattern.captureText("가격 1000원", mapOf("input1" to TaskParameter("input1", "1"))))
    }
}
