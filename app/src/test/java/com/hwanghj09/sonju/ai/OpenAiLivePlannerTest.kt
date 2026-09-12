package com.hwanghj09.sonju.ai

import com.hwanghj09.sonju.BuildConfig
import com.hwanghj09.sonju.agent.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Opt-in network check: SONJU_LIVE_MODEL=1; uses only synthetic, non-personal screens. */
class OpenAiLivePlannerTest {
    @Test fun unfamiliarLibraryLookupUsesSearchSubmissionAndVerifiesTheResultWithoutASkill() {
        assumeTrue(System.getenv("SONJU_LIVE_MODEL") == "1")
        val command = "별빛 도서관에서 우주 여행을 검색해서 상세 화면의 출판사를 알려줘"
        val packageName = "org.example.library"
        fun field(text: String) = UiElement("0.query", "$packageName:id/catalog_search", "android.widget.EditText",
            text, null, ScreenBounds(0, 100, 450, 200), true, true, false, true, true, false,
            hintText = "도서 검색", availableActions = setOf(UiNodeAction.SET_TEXT, UiNodeAction.IME_ENTER))
        fun label(path: String, text: String, clickable: Boolean) = UiElement(path, null, "android.widget.TextView",
            text, null, ScreenBounds(0, 250, 450, 400), clickable, false, false, true, true, false)
        val screens = listOf(
            listOf(label("0.search", "도서 찾기", true)),
            listOf(field("")),
            listOf(field("우주 여행"), label("0.hint", "입력한 검색어를 키보드 검색 키로 제출해 주세요", false)),
            listOf(label("0.book", "우주 여행 · 상세 정보 보기", true)),
            listOf(label("0.details", "우주 여행 상세 정보: 출판사 별빛출판, 분야 천문학", false)),
        ).mapIndexed { index, nodes -> UiSnapshot(packageName, "별빛 도서관", epoch = index + 1L, elements = nodes) }
        val runtime = SonjuAgentRuntime.createForTest(com.hwanghj09.sonju.skill.InMemorySkillRepository(),
            object : com.hwanghj09.sonju.logging.ProcessLogRepository {
                override fun append(step: com.hwanghj09.sonju.logging.TraceStep) = Unit
                override fun exportRedacted(): List<String> = emptyList()
            })
        val session = AutonomySession(command, screens.first(), 0)
        val expected = listOf(ActionType.CLICK, ActionType.SET_TEXT, ActionType.SUBMIT_TEXT, ActionType.CLICK)
        OpenAiPlanner(installedApps = { listOf(InstalledApp("별빛 도서관", packageName)) }).use { planner ->
            screens.forEachIndexed { index, snapshot ->
                assertNull("Must run without a stored skill", runtime.fastPathPlan(command, snapshot, session))
                val candidate = awaitPlan { callback -> planner.planAsync(command, snapshot, null,
                    autonomyContext = session.plannerContext(snapshot, null), callback = callback) }
                val plan = session.acceptPlan(candidate, snapshot)
                if (index == screens.lastIndex) {
                    assertTrue("Completion needs the actual publisher", runtime.goalSatisfied(command, plan, snapshot, session))
                    assertTrue(plan.summary.contains("별빛출판"))
                } else {
                    assertFalse(plan.goalCompleted)
                    assertEquals(expected[index], plan.actions.first().type)
                    val verified = runtime.verify(command, plan, snapshot)
                    assertTrue("Every model step must pass the real verifier: $verified",
                        verified is com.hwanghj09.sonju.verifier.VerificationResult.Allowed)
                    val action = (verified as com.hwanghj09.sonju.verifier.VerificationResult.Allowed)
                        .verifiedPlan.actions.values.single()
                    if (action.action.type in setOf(ActionType.SET_TEXT, ActionType.SUBMIT_TEXT)) {
                        assertEquals("우주 여행", action.action.value)
                    }
                    session.recordExecution(plan, snapshot, ExecutionResult(true, "fixture transition observed", 1,
                        postconditionSatisfied = true), action.resolvedNodeId)
                    session.observe(screens[index + 1])
                }
            }
        }
    }

    @Test fun tellMeTomorrowForecastRequiresLookupThenReturnsTheObservedValues() {
        assumeTrue(System.getenv("SONJU_LIVE_MODEL") == "1")
        OpenAiPlanner().use { planner ->
            val command = "내일 날씨 알려줘"
            val before = snapshot("내일 예보 보기", clickable = true)
            val step = awaitPlan { callback -> planner.planAsync(command, before, null, callback = callback) }
            assertEquals(ActionType.CLICK, step.actions.first().type)
            assertFalse(step.goalCompleted)
            val after = snapshot("내일 예보: 서울, 맑음, 최저 19도, 최고 27도", clickable = false)
            val done = awaitPlan { callback -> planner.planAsync(command, after, null,
                autonomyContext = "내일 예보 보기 버튼을 눌렀으며 새로 관찰한 결과 화면이다.", callback = callback) }
            assertTrue(done.goalCompleted)
            assertTrue(done.goalChecks.isNotEmpty())
            assertTrue(done.goalChecks.all { it.matches(after) })
            assertTrue(done.summary, done.summary.contains("19") && done.summary.contains("27"))
            assertTrue(done.summary, done.summary.contains("맑"))
        }
    }

    @Test fun lunaPlansAnAccessibilityStepAndGroundedCompletion() {
        assumeTrue(System.getenv("SONJU_LIVE_MODEL") == "1")
        OpenAiPlanner().use { planner ->
            assertTrue("Configure OPENAI_API_KEY locally before running this check", planner.isConfigured)
            assertEquals("gpt-5.6-luna", BuildConfig.OPENAI_MODEL)
            val before = snapshot("Start", clickable = true)
            val step = awaitPlan { callback -> planner.planAsync(
                "Open the result screen using the Start button", before, null, callback = callback) }
            assertEquals(PlanSource.OPENAI_STRUCTURE, step.source)
            assertEquals(ActionType.CLICK, step.actions.first().type)
            assertFalse(step.goalCompleted)
            assertFalse(step.visualFallback)
            val intent = com.hwanghj09.sonju.task.DeterministicTaskParser.parse(step.goal)
            val screen = com.hwanghj09.sonju.perception.AccessibilityScreenParser.parse(before)
            val task = com.hwanghj09.sonju.task.DeterministicTaskCanonicalizer.canonicalize(intent, screen)
            assertTrue(com.hwanghj09.sonju.verifier.DeterministicActionVerifier()
                .verify(intent, task, step, before, screen, false) is
                com.hwanghj09.sonju.verifier.VerificationResult.Allowed)
            val after = snapshot("Result screen — task complete", clickable = false)
            val done = awaitPlan { callback -> planner.planAsync(
                "Open the result screen using the Start button", after, null,
                autonomyContext = "The Start button was clicked successfully. This is the newly observed result screen.",
                callback = callback) }
            assertTrue(done.goalCompleted)
            assertTrue(done.goalChecks.isNotEmpty())
            assertTrue("Synthetic completion evidence: ${done.goalChecks}", done.goalChecks.all { it.matches(after) })
        }
    }

    @Test fun lunaPlansFromPixelsWhenAccessibilityHasNoTarget() {
        assumeTrue(System.getenv("SONJU_LIVE_MODEL") == "1")
        OpenAiPlanner().use { planner ->
            val image = BufferedImage(480, 800, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                color = Color.WHITE
                fillRect(0, 0, 480, 800)
                color = Color.BLUE
                fillRect(100, 300, 280, 120)
                color = Color.WHITE
                font = font.deriveFont(26f)
                drawString("CONTINUE", 155, 370)
                dispose()
            }
            val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
            val jpeg = Base64.getEncoder().encodeToString(bytes)
            val plan = awaitPlan { callback -> planner.planScreenshotAsync(
                "Tap CONTINUE to reach the next page", UiSnapshot("com.example.fixture", "Fixture", epoch = 1,
                    elements = emptyList(), windowBounds = ScreenBounds(0, 0, 480, 800)), jpeg,
                "Two accessibility attempts found no actionable nodes. Use the current screenshot.", callback) }
            val click = plan.actions.first { it.type != ActionType.FINISH }
            assertEquals(ActionType.CLICK_COORDINATE, click.type)
            assertTrue("Synthetic button coordinates: (${click.xRatio}, ${click.yRatio})",
                click.xRatio!! in .21..0.79 && click.yRatio!! in .375..0.525)
            assertEquals(visualFrameHash(jpeg), plan.visualFrameHash)
            assertFalse(plan.visualFrameVerified)
        }
    }

    private fun awaitPlan(start: ((Result<AgentPlan>) -> Unit) -> Unit): AgentPlan {
        val latch = CountDownLatch(1)
        var result: Result<AgentPlan>? = null
        start { result = it; latch.countDown() }
        assertTrue("Planner did not respond within 30 seconds", latch.await(30, TimeUnit.SECONDS))
        return requireNotNull(result).getOrThrow()
    }

    private fun snapshot(label: String, clickable: Boolean) = UiSnapshot("com.example.fixture", "Fixture", epoch = 1,
        elements = listOf(UiElement("0", "com.example.fixture:id/content", "android.widget.Button", label, null,
            ScreenBounds(0, 0, 400, 200), clickable, false, false, true, true, false)))
}
