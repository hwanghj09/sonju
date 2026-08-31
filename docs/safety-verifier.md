# Safety Verifier 계약

## 허용 조건

`DeterministicActionVerifier`는 다음 조건을 모두 만족할 때만 `VerifiedPlan`을 발급한다.

- 실행 행동이 정확히 하나이고 planner confidence가 허용 범위다.
- 외부 앱의 현재 화면을 관찰했으며 plan과 snapshot이 같은 epoch/fingerprint에 묶인다.
- click/set-text 대상이 semantic grounder에서 유일하게 결정되고, 실제 실행 node id가 고정된다.
- set-text 값이 비어 있지 않고 대상이 editable이며 민감 입력이 아니다.
- 일반 plan에는 좌표가 없다. 좌표는 안전한 VLM 폴백의 0..1 범위 값만 허용한다.
- 잘린 tree의 node action은 재관찰하고, 고위험 행동이면 fail closed 한다.
- task constraint와 risk policy를 통과했다.

## 위험 경계

결제·송금·구매 같은 critical final commit과 인증정보·비밀번호·OTP 입력은 자동 실행하지 않는다. 택시 호출처럼 현실 세계에 즉시 영향을 주는 high-risk commit은 실행 직전 사용자의 명시적 확인이 필요하다. 확인은 다른 화면이나 다른 plan으로 재사용할 수 없다.

## 실행 후 검증

click, set-text, 좌표 행동은 화면 revision 또는 기대 fingerprint 변화가 관찰돼야 성공이다. 제한 시간 안에 postcondition이 만족되지 않으면 `POSTCONDITION_TIMEOUT`으로 실패하고 재계획한다. 최종 성공은 별도의 goal evaluator가 현재 `ScreenState`에서 확인한다.

실행 실패는 grounding ambiguity/not-found, node/gesture failure, timeout, loop, model/VLM failure, cancellation 등 구조화된 `ExecutionFailureReason`으로 기록한다. process log에는 원문 요청 대신 hash와 redacted selector/evidence만 남긴다.
