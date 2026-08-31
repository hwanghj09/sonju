# Sonju 에이전트 아키텍처 계약

이 문서는 사용자 제공 `SONJU_AGENT_ARCHITECTURE.md`를 이 저장소에서 구현할 때 지켜야 할 핵심 계약을 요약한다. 세부 구현은 [architecture.md](architecture.md), skill 저장 형식은 [skill-format.md](skill-format.md), 실행 허용 규칙은 [safety-verifier.md](safety-verifier.md)를 따른다.

## 불변식

1. 모델은 행동 후보를 제안할 뿐, Android API를 직접 호출하지 않는다.
2. 모든 실행 가능한 계획은 현재 `UiSnapshot`에서 생성된 `ScreenState`와 결정적 verifier를 통과해야 한다.
3. verifier는 정확히 한 개의 실행 행동을 현재 화면 revision, fingerprint, node id에 묶은 `VerifiedPlan`만 만든다.
4. executor는 raw `AgentPlan`을 받지 않으며 `VerifiedPlan`에 없는 노드나 좌표를 다시 추측하지 않는다.
5. 접근성 node action과 clickable ancestor를 먼저 사용하고 gesture는 제한된 폴백으로만 사용한다.
6. 일반 planner는 좌표 행동을 만들 수 없다. 좌표는 안전한 화면에서 명시적으로 활성화된 VLM 폴백 결과만 허용한다.
7. 행동 전 상태를 확인하고, 행동 뒤 화면 revision 및 postcondition을 관찰한다. 관찰된 최종 goal state 전에는 성공을 반환하지 않는다.
8. retry, timeout, loop, session budget은 유한해야 하며 사용자가 취소하면 즉시 중단한다.
9. 성공 trace는 원문 명령·스크린샷·민감 입력을 저장하지 않고 parameterized `AppSkill`로만 일반화한다.
10. known skill은 local parser, matcher, verifier, accessibility executor만으로 오프라인 fast path를 구성한다.

## 기준 실행 흐름

```text
AccessibilityService
  -> UiSnapshot
  -> AccessibilityScreenParser / ScreenState
  -> CanonicalTask + SkillRetriever
  -> FastPathPlanner 또는 structured Gemini planner
  -> SemanticGrounder
  -> DeterministicActionVerifier
  -> VerifiedPlan
  -> Accessibility action executor
  -> event 기반 postcondition / goal verification
  -> redacted process log / successful skill learning
```

## MVP 완료 기준

MVP는 semantic screen parsing과 pruning, node grounding, direct action 우선 실행, pre/postcondition, 유한 retry·timeout·loop, cancellation, canonical task, local skill 저장·조회·parameter filling·fast path, structured unknown-task planner, verifier를 통과하는 VLM 폴백, redacted process log, 최종 goal 검증을 모두 포함한다.

실기기/API 호환성, 배터리·메모리 프로파일링, 앱별 adapter 확대와 Play 정책 승인은 production-candidate 검증 항목이며 MVP 단위 테스트나 APK 빌드만으로 완료됐다고 간주하지 않는다.
