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
    }
}
