# 구현 아키텍처

## 계층

| 계층 | 구현 | 책임 |
| --- | --- | --- |
| Perception | `perception/AccessibilityScreenParser` | `UiSnapshot`을 정규화·pruning하고 semantic role, screen type, quality, 안정 fingerprint를 생성 |
| Task | `task/DeterministicTaskParser`, `DeterministicTaskCanonicalizer` | 요청을 parameter, constraint, risk가 있는 `CanonicalTask`로 변환하고 bounded cache 사용 |
| Skill | `skill/SkillRetriever`, `FastPathPlanner`, `SkillLearner` | 화면과 task가 정확히 맞는 local skill 재사용 및 성공 trace 일반화 |
| Planning | `planner/Plan`, `OpenAiPlanner` | semantic action 후보 생성. unknown task는 OpenAI Responses strict JSON Schema structured output 사용 |
| Grounding | `grounding/DeterministicSemanticGrounder` | selector, text, id, role, state를 점수화하고 유일한 node 또는 clickable ancestor를 반환 |
| Verification | `verifier/DeterministicActionVerifier` | 현재 화면, risk, 민감성, 좌표 출처, node 유일성, 사용자 확인을 검사하고 `VerifiedPlan` 발급 |
| Execution | `SonjuAccessibilityService` | 검증된 node action을 우선 실행하고 제한된 gesture 폴백 뒤 event 기반 postcondition 확인 |
| Memory/logging | `LocalSkillRepository`, `LocalProcessLogRepository` | 제한된 JSON 저장, confidence 갱신, redacted trace 기록 |
| Composition | `agent/SonjuAgentRuntime` | fast path, verifier, goal evaluator, skill 학습, process log 연결 |

## 실행 권한 경계

`VerifiedAction`과 `VerifiedPlan`의 생성자는 module 내부에서만 접근할 수 있다. `MainActivity`와 접근성 오버레이는 raw plan을 verifier에 전달하고, executor에는 허용 결과만 넘긴다. 서비스는 실행 직전 source epoch와 raw/semantic fingerprint를 다시 비교하며, 각 step은 verifier가 고정한 node id만 사용한다.

화면이 변하거나 grounding이 모호하면 실행하지 않고 재계획한다. node click이 실패했을 때도 동일한 검증 node의 bounds에만 gesture를 보낼 수 있다. VLM 좌표는 `visualFallback=true`와 `OPENAI_SEMANTIC_MAP` 출처가 함께 있어야 한다.

## 관찰과 종료

`AccessibilityEventMonitor`가 window/content revision 변화를 기다리며 각 행동의 postcondition timeout을 제한한다. `AutonomySession`은 화면-행동 반복, 2-state cycle, 연속 무변화를 차단하고 step/time budget 및 cancellation을 유지한다. `FINISH` 제안만으로 종료하지 않고 `DeterministicGoalEvaluator`가 현재 관찰에서 goal을 확인해야 한다.

## 저장 선택

계약은 Room을 초기 권장사항으로 두지만 local-only MVP를 허용한다. 현재 구현은 추가 데이터베이스 의존성 없이 `SkillRepository`와 `ProcessLogRepository` 추상화 뒤에 크기가 제한된 `SharedPreferences` JSON 저장소를 둔다. 향후 Room이나 remote store로 교체해도 planner와 verifier는 바뀌지 않는다.
