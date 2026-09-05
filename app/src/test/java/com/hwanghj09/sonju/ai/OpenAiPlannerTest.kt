package com.hwanghj09.sonju.ai

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.agent.RiskLevel
import com.hwanghj09.sonju.agent.UiSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiPlannerTest {
    @Test
    fun planRequestUsesResponsesStructuredOutputWithoutEmbeddingTheKey() {
        withPlanner { planner ->
            val request = planner.buildPlanRequest(
                command = "설정을 열어 줘",
                snapshot = snapshot(),
            )

            assertEquals("gpt-5.6-luna", request.getString("model"))
            assertEquals("none", request.getJSONObject("reasoning").getString("effort"))
            assertFalse(request.getBoolean("store"))
            assertFalse(request.has("response_format"))
            assertFalse(request.toString().contains("test-api-key"))

            val format = request.getJSONObject("text").getJSONObject("format")
            assertEquals("json_schema", format.getString("type"))
            assertEquals("sonju_agent_plan", format.getString("name"))
            assertTrue(format.getBoolean("strict"))
            assertFalse(
                actionTypeNames(format.getJSONObject("schema"))
                    .contains(ActionType.CLICK_COORDINATE.name),
            )

            val input = request.getJSONArray("input").getJSONObject(0)
            assertEquals("user", input.getString("role"))
            assertEquals("input_text", input.getJSONArray("content").getJSONObject(0).getString("type"))
        }
    }

    @Test
    fun semanticMapRequestUsesDataUrlAndAllowsCoordinateProposal() {
        withPlanner { planner ->
            val request = planner.buildPlanRequest(
                command = "화면 전용 버튼을 눌러 줘",
                snapshot = snapshot(),
                semanticMapJpegBase64 = "AQID",
            )

            val content = request.getJSONArray("input").getJSONObject(0).getJSONArray("content")
            val image = content.getJSONObject(1)
            assertEquals("input_image", image.getString("type"))
            assertEquals("data:image/jpeg;base64,AQID", image.getString("image_url"))
            assertTrue(
                actionTypeNames(request.getJSONObject("text").getJSONObject("format").getJSONObject("schema"))
                    .contains(ActionType.CLICK_COORDINATE.name),
            )
        }
    }

    @Test
    fun completedResponseSkipsReasoningItemsAndParsesTheStructuredPlan() {
        withPlanner { planner ->
            val plan = planner.parsePlanResponse(completedResponse(validPlanJson()))

            assertEquals("설정 화면 열기", plan.goal)
            assertEquals(PlanSource.OPENAI_STRUCTURE, plan.source)
            assertEquals(RiskLevel.LOW, plan.modelRisk)
            assertEquals(ActionType.OPEN_APP, plan.actions.single().type)
            assertEquals("설정", plan.actions.single().target)
            assertTrue(plan.continueAfterAction)
            assertFalse(plan.goalCompleted)
        }
    }

    @Test
    fun semanticMapResponseIsMarkedAsVisualModelProvenance() {
        withPlanner { planner ->
            val plan = planner.parsePlanResponse(
                completedResponse(validPlanJson()),
                usedSemanticMap = true,
            )

            assertEquals(PlanSource.OPENAI_SEMANTIC_MAP, plan.source)
            assertTrue(plan.visualFallback)
        }
    }

    @Test
    fun refusalAndIncompleteResponsesFailClosed() {
        withPlanner { planner ->
            expectFailure("OpenAI refused the request") {
                planner.parsePlanResponse(
                    JSONObject()
                        .put("status", "completed")
                        .put(
                            "output",
                            JSONArray().put(
                                JSONObject().put("type", "message").put(
                                    "content",
                                    JSONArray()
                                        .put(
                                            JSONObject().put("type", "refusal")
                                                .put("refusal", "cannot comply"),
                                        )
                                        .put(
                                            JSONObject().put("type", "output_text")
                                                .put("text", validPlanJson().toString()),
                                        ),
                                ),
                            ),
                        ).toString(),
                )
            }
            expectFailure("OpenAI response was incomplete") {
                planner.parsePlanResponse(
                    JSONObject().put("status", "incomplete").put("output", JSONArray()).toString(),
                )
            }
        }
    }

    @Test
    fun malformedPlanAndHttpErrorsExposeOnlyStableFailureMessages() {
        withPlanner { planner ->
            val malformed = validPlanJson().apply { remove("actions") }
            expectFailure("OpenAI returned an invalid structured plan") {
                planner.parsePlanResponse(completedResponse(malformed))
            }

            assertEquals(
                "OpenAI API key is invalid",
                planner.failureMessage(401, "{\"error\":{\"message\":\"secret detail\"}}"),
            )
            assertEquals(
                "OpenAI request was rate limited",
                planner.failureMessage(429, "{\"error\":{\"message\":\"account detail\"}}"),
            )
            assertEquals(
                "OpenAI request failed with HTTP 503",
                planner.failureMessage(503, "upstream included sensitive diagnostics"),
            )
        }
    }

    @Test
    fun blankKeyIsNotConfigured() {
        val planner = OpenAiPlanner(apiKey = "", model = "gpt-5.6-luna")
        try {
            assertFalse(planner.isConfigured)
        } finally {
            planner.close()
        }
    }

    private fun validPlanJson(): JSONObject = JSONObject()
        .put("final_goal", "설정 화면 열기")
        .put("target_app", "설정")
        .put("target_surface", "설정 홈")
        .put("required_tools", JSONArray().put("OPEN_APP"))
        .put("strategy", JSONArray().put("설정 앱을 연다"))
        .put("success_criteria", JSONArray().put("설정 화면이 보인다"))
        .put("revision_reason", "첫 계획")
        .put("summary", "설정 앱을 엽니다.")
        .put("risk", "LOW")
        .put("confidence", 0.93)
        .put("continue_after_action", true)
        .put("goal_completed", false)
        .put(
            "actions",
            JSONArray().put(
                JSONObject()
                    .put("type", "OPEN_APP")
                    .put("description", "설정 앱을 엽니다.")
                    .put("target", "설정")
                    .put("value", JSONObject.NULL)
                    .put("wait_millis", 0)
                    .put("x_ratio", JSONObject.NULL)
                    .put("y_ratio", JSONObject.NULL),
            ),
        )

    private fun snapshot(): UiSnapshot = UiSnapshot(
        packageName = "com.android.settings",
        windowTitle = "설정",
        epoch = 7L,
        elements = emptyList(),
    )

    private fun completedResponse(plan: JSONObject): String = JSONObject()
        .put("status", "completed")
        .put(
            "output",
            JSONArray()
                .put(JSONObject().put("type", "reasoning").put("summary", JSONArray()))
                .put(
                    JSONObject()
                        .put("type", "message")
                        .put("role", "assistant")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject().put("type", "output_text").put("text", plan.toString()),
                            ),
                        ),
                ),
        ).toString()

    private fun actionTypeNames(schema: JSONObject): Set<String> {
        val values = schema.getJSONObject("properties")
            .getJSONObject("actions")
            .getJSONObject("items")
            .getJSONObject("properties")
            .getJSONObject("type")
            .getJSONArray("enum")
        return (0 until values.length()).mapTo(mutableSetOf()) { values.getString(it) }
    }

    private inline fun withPlanner(block: (OpenAiPlanner) -> Unit) {
        val planner = OpenAiPlanner(apiKey = "test-api-key", model = "gpt-5.6-luna")
        try {
            block(planner)
        } finally {
            planner.close()
        }
    }

    private inline fun expectFailure(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail("Expected OpenAiPlannerException")
        } catch (error: OpenAiPlannerException) {
            assertEquals(expectedMessage, error.message)
        }
    }
}
