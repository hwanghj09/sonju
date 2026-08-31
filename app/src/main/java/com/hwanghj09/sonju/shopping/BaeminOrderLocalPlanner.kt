package com.hwanghj09.sonju.shopping

import com.hwanghj09.sonju.agent.ActionType
import com.hwanghj09.sonju.agent.AgentAction
import com.hwanghj09.sonju.agent.AgentPlan
import com.hwanghj09.sonju.agent.PlanSource
import com.hwanghj09.sonju.agent.RiskLevel
import com.hwanghj09.sonju.agent.UiSnapshot

/** Deterministic, stepwise entry into a Baemin order workflow. */
object BaeminOrderLocalPlanner {
    fun plan(command: String, snapshot: UiSnapshot): AgentPlan? {
        val request = BaeminOrderRequestParser.parse(command) ?: return null
        val action = if (snapshot.packageName != BaeminNavigator.PACKAGE_NAME) {
            AgentAction(ActionType.OPEN_APP, "배민 앱을 엽니다.", target = "배민")
        } else {
            when (val next = BaeminNavigator.nextSearchAction(snapshot, request.query)) {
                is BaeminScreenAction.Click -> AgentAction(
                    ActionType.CLICK,
                    "배민에서 '${request.query}' 검색 경로를 진행합니다.",
                    target = next.path,
                )
                is BaeminScreenAction.SetSearchText -> AgentAction(
                    ActionType.SET_TEXT,
                    "배민 검색창에 요청한 메뉴를 입력합니다.",
                    target = next.path,
                    value = next.value,
                )
                BaeminScreenAction.Wait -> AgentAction(
                    ActionType.WAIT,
                    "배민 화면이 준비될 때까지 기다립니다.",
                    waitMillis = 700L,
                )
                is BaeminScreenAction.Scroll,
                is BaeminScreenAction.Stop,
                BaeminScreenAction.Complete,
                -> return null
            }
        }
        return AgentPlan(
            goal = command.trim(),
            summary = action.description,
            modelRisk = RiskLevel.MEDIUM,
            confidence = 1.0,
            actions = listOf(action, AgentAction(ActionType.FINISH, "변경된 화면을 다시 관찰합니다.")),
            source = PlanSource.LOCAL_RULE,
            continueAfterAction = true,
            targetApp = BaeminNavigator.PACKAGE_NAME,
            targetSurface = "배민에서 '${request.query}' 검색 및 주문 선택 화면",
            requiredTools = setOf(action.type),
            strategy = listOf(
                "배민 실행",
                "검색 화면 확인",
                "검색어 '${request.query}' 입력",
                "화면을 재관찰하며 식당·메뉴·옵션을 하나씩 검증",
                "주문 확정 또는 결제 전 사용자 확인",
            ),
            successCriteria = listOf("새 주문 접수 상태가 화면에서 확인됨"),
            revisionReason = "배민 주문 명령에서 앱과 검색어를 로컬에서 확정한 안전한 시작 경로",
        )
    }
}
