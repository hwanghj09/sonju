# SonjuAI 자율 조작 아키텍처

## 목표

SonjuAI는 특정 앱의 고정 화면 순서를 재생하지 않습니다. 사용자 요청마다 최종 목표를 한 번 고정하고, 현재 화면을 관찰해 다음 도구 하나를 실행한 뒤 결과를 다시 계획에 반영합니다. 접근성 의미 정보를 가장 저렴하고 빠른 센서로 사용하고, 의미 정보가 부족한 안전한 화면에서만 시각 분석과 좌표 도구를 사용합니다.

기본 명령 파이프라인은 과거 배민 전용 navigator를 호출하지 않습니다. 해당 코드는 이전 빌드 호환용으로 격리되어 있으며 `startBaeminOrder`는 deprecated 상태입니다.

## 필수 계획 계약

`OpenAiPlanner`의 strict JSON Schema는 매 응답에서 다음 항목을 요구합니다.

```json
{
  "final_goal": "사용자가 원하는 변경 불가능한 최종 결과",
  "target_app": "실행해야 할 앱",
  "target_surface": "도착하거나 작동시켜야 할 페이지 또는 기능",
  "required_tools": ["OPEN_APP", "CLICK", "SET_TEXT"],
  "strategy": ["앱 열기", "검색 열기", "검색어 입력", "결과 선택"],
  "success_criteria": ["요청한 결과의 상세 화면이 보인다"],
  "revision_reason": "화면 변화나 실패로 계획을 바꾼 이유",
  "summary": "현재 계획 요약",
  "risk": "LOW",
  "confidence": 0.9,
  "continue_after_action": true,
  "goal_completed": false,
  "goal_checks": [],
  "actions": [
    {
      "type": "CLICK",
      "description": "검색 버튼을 누른다",
      "target": "검색",
      "value": null,
      "wait_millis": 0,
      "x_ratio": null,
      "y_ratio": null
    },
    {
      "type": "FINISH",
      "description": "변경된 화면을 다시 관찰한다",
      "target": null,
      "value": null,
      "wait_millis": 0,
      "x_ratio": null,
      "y_ratio": null
    }
  ]
}
```

`AutonomySession.acceptPlan`이 모델 응답을 다시 정규화합니다.

- `final_goal`은 최초 사용자 명령으로 강제되며 이후 모델 응답으로 바뀌지 않습니다.
- 실행 앱, 목표 화면, 전략, 도구 집합, 완료 조건은 관찰에 따라 바뀔 수 있습니다.
- 관찰하지 않은 미래 화면을 전제로 여러 행동을 반환해도 첫 행동 하나만 남깁니다.
- 로컬 revision은 세션이 증가시키며 모델이 이전 revision으로 되돌릴 수 없습니다.
- `goal_completed=true`이면 실행 행동을 제거하고 `FINISH`만 허용합니다.

## 실행 루프

1. 사용자 명령과 최초 `UiSnapshot`으로 `AutonomySession`을 만듭니다.
2. `UiTreeReader`가 최대 2,000개 노드·깊이 24까지 의미 구조를 읽습니다.
3. 민감값을 지운 compact tree, 최근 도구 결과, 반복 실패, 과거 최단 경로를 계획기에 전달합니다.
4. 로컬 계약 검사와 `EssentialSafetyPolicy`를 통과한 다음 도구 하나를 실행합니다.
5. 실행 직전에 live package·window·epoch·전체 fingerprint를 다시 확인합니다.
6. Android가 동작을 접수한 뒤 화면을 새로 캡처합니다.
7. 화면 변화나 실패를 세션 trace에 기록하고, 완료 조건이 아니면 3단계로 돌아갑니다.
8. 완료 화면이 확인되면 성공 trace에서 순환을 제거하고 더 짧은 경로를 저장합니다.

세션 상한은 도구 24개, AI 호출 24개와 3분입니다. 같은 화면에서 같은 action signature가 두 번 실패하면 다음 프롬프트의 변경 필수 목록에 들어갑니다. 사용자가 중단하면 진행 중인 네트워크 요청, gesture, 실행 generation을 무효화합니다.

## 화면 관찰

`UiElement`는 다음 접근성 정보를 모델 문맥과 실행기에 제공합니다.

- text, content description, hint, state description, pane title, tooltip
- view ID, class, node path, 화면 bounds
- clickable, editable, scrollable, checkable/checked, selected
- focusable/focused, accessibility focused, long-clickable, dismissable, heading
- click, long click, set text, 방향별 scroll, focus, expand/collapse, dismiss 등 실제 지원 action

비밀번호나 인증번호, 카드·주민 정보, 분할 OTP 슬롯과 인접 문맥은 redaction 후 모델 문맥에서 path·텍스트·좌표를 제외합니다. 민감 노드가 있는 화면은 원본 스크린샷 원격 분석도 금지합니다.

## 범용 도구

| 도구 | 우선 실행 방식 | 폴백 또는 검증 |
|---|---|---|
| `OPEN_APP` | 실제 설치 목록의 package 또는 유일한 label/alias를 확인한 뒤 launcher intent | 앱 package 재관찰 |
| `CLICK` | 유일한 selector를 clickable node/ancestor로 해석해 `ACTION_CLICK` | 실패 결과를 재계획에 전달 |
| `CLICK_COORDINATE` | 0~1 좌표를 display 좌표로 변환해 `dispatchGesture` | live revision과 좌표 범위 확인 |
| `SET_TEXT` | 유일한 editable node에 focus 후 `ACTION_SET_TEXT` | 값 길이 4,000자 제한 |
| 방향 스크롤 | node가 노출한 방향 action | 중앙 swipe gesture |
| `BACK/HOME` | accessibility global action | 새 package/화면 관찰 |
| 알림/빠른 설정 | accessibility global action | System UI package 관찰 |
| `WAIT` | 100~2,000ms bounded wait | 도구 예산에 포함 |

접근성 selector는 path, 전체/suffix view ID, 정확한 text/description/hint, 정규화된 부분 일치 순으로 점수를 줍니다. 최고 점수 후보가 둘이면 임의로 첫 번째를 누르지 않습니다.

## 좌표와 시각 폴백

같은 화면에서 접근성 구조 모델을 먼저 시도하고 실제 대상 탐색이 두 번 실패한 경우, 비민감·비로딩·완전한 트리 조건에서만 Android 11 이상의 원본 캡처를 시각 모델에 보냅니다. 이미지 시도는 의미 화면당 1회, 요청당 최대 2회입니다. 반환 좌표는 직접 실행하지 않고 `CLICK_COORDINATE`가 포함된 정식 계획으로 변환합니다. 따라서 좌표 탭도 세션 이력, 사용자 확인, live revision, 도구 예산, 성공 경로 학습을 동일하게 거칩니다.

`FLAG_SECURE`, 캡처 실패, Android 10 이하, 모델 미설정, gesture 거부 시 좌표 도구는 실패로 기록되고 다른 접근성 경로를 재계획합니다.

## 최단 성공 경로 기억

`LearnedRouteMemory`는 SharedPreferences에 최대 80개·90일 경로를 저장합니다.

1. 실패한 도구와 `WAIT/FINISH`를 제거합니다.
2. `A → B → A`처럼 이전 fingerprint로 되돌아온 구간을 loop erasure로 제거합니다.
3. 앱 열기 0.8, node 클릭 1.0, 입력 1.2, 스크롤 1.4, 좌표 클릭 1.8 등 비용을 합산합니다.
4. 같은 목표·시작 package·시작 fingerprint의 기존 경로보다 비용이 낮을 때만 교체합니다.
5. 정확한 시작 화면뿐 아니라 개인정보를 지운 목표 token 유사도와 package를 이용해 관련 경로를 계획 힌트로 제공합니다.

저장되는 것은 action type, 안정 selector, 정규화 좌표뿐입니다. `SET_TEXT.value`, 원본 명령, 화면 본문, 스크린샷은 저장하지 않습니다. 재사용 경로는 자동 replay하지 않고 현재 화면과 맞는지 모델과 live executor가 다시 판단합니다.

## 최소 안전 경계

일반 앱 탐색·클릭·스크롤·검색 입력은 자동 실행합니다. 다음 경우만 사용자 확인을 요구합니다.

- 결제, 송금, 구매, 주문을 최종 확정할 수 있는 클릭
- 전화번호, 주소, 이메일, 계좌·카드, 비밀번호, PIN, OTP 등 개인정보·인증정보 입력
- 민감 노드가 존재하는 화면의 불투명한 좌표 클릭

잘못된 좌표, 입력값 누락, 편집 필드 모호성, 여러 speculative action은 실행 정확성 계약 위반으로 차단하고 재계획합니다. Android의 접근성 활성화, 제한된 설정, 마이크 권한, 잠금·생체 인증 등 시스템 보안 경계는 우회하지 않습니다.

## 비용과 속도

- 의미 노드가 충분하면 이미지 없이 compact tree만 전송합니다.
- 단순 시스템 명령은 로컬 규칙으로 계획 계약을 채웁니다.
- 모델은 전체 미래 행동 대신 다음 행동 하나만 정밀하게 반환합니다.
- semantic fingerprint와 route hint로 이미 실패한 행동 및 장거리 경로의 반복을 줄입니다.
- snapshot traversal은 단일 백그라운드 executor에서 처리하고 실제 Android action만 메인 스레드에서 수행합니다.

## 검증

한글 경로 체크아웃에서는 Gradle test worker가 생성된 Kotlin test class를 찾지 못하는 Windows 문제가 재현됩니다. 저장소를 복사하지 않고 임시 ASCII drive를 매핑해 검증할 수 있습니다.

```powershell
subst R: "C:\Users\Taeyoon\Desktop\태윤\Codes\sonju"
try {
    Push-Location R:\
    $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
    .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
} finally {
    Pop-Location
    subst R: /d
}
```

JVM 테스트는 계획 목표 불변성, 한 행동 제한, 반복 실패 피드백, 경로 순환 제거, 입력값 비저장, 결제·개인정보 확인, screenshot privacy, selector 유일성을 검증합니다. 실제 좌표·키보드·OEM scroll action·앱 전환은 실기기 instrumentation 검증이 별도로 필요합니다.


## AI recovery and structured AppSkill (2026-09-06)

Typed and voice execution commands enter the Accessibility service. It tries an active AppSkill,
then local planners, then the existing `gpt-5.6-luna` Responses client. Planning/grounding failures
and execution failures hand the current task to the model, preserving its original goal and history.
Recovery skips the failing local planner and cached skill for the rest of that session.

Normal model requests contain the compact Accessibility tree without an image, use reasoning `none`,
and cap output at 1,800 tokens. Two consecutive Accessibility failures enable screenshot planning
for that task only. VLM uses the same one-action planning contract and reobserves after every action.
Screenshot capture still enforces the existing privacy, revision, Android version and FLAG_SECURE boundaries.
Image-assisted semantic actions use live node verification; only coordinate actions and pixel-only
completion require an exact fresh image match. An unrelated blinking cursor cannot invalidate a semantic action.

Completion needs observed evidence. General model completions supply `goal_checks` with a unique
visible non-sensitive `selector`, exact `text` (or null), and `checked` (or null). Pixel-only completion
requires a fresh matching image and observed task progress plus model-reported visual evidence;
this is model assessment rather than a deterministic semantic proof. High-risk completion policies remain.

Only a verified complete task is saved as an AppSkill. For Accessibility transitions, breadth-first
search retains the fewest observed tool calls; it does not claim optimality over undiscovered app paths.
A healthy shorter skill is retained. Visual transitions keep their observed order because identical
Accessibility trees do not distinguish different Canvas states. Stored inputs are parameter references
or character spans of the current request, never copied input values. Exact request hashes prevent
unrelated goals or changed literals from sharing a cached outcome; arbitrary paraphrase reuse is not promised.

Replay tracks a step index, validates current selectors and postconditions, and makes no model calls
when they match. Visual steps store coordinates, their semantic description and an image digest;
replay requires an exact fresh image match. Clock/animation/layout changes can reduce visual cache hits.
Pixels and raw requests are not persisted. Skills from before request binding are learned again.
Clicks prefer unique resource IDs, then unique visible labels, then structural node paths. Grounding
traverses ancestor paths even when the snapshot omitted an inert intermediate layout. Learning follows
loading observations until the next action and folds clicks rejected before dispatch as observation delays.

On failure, the session retains skill ID, version, failed step and recovery trace offset. Once the model
completes the same goal, the working prefix is retained and the failed suffix is replaced, under the
same ID with an incremented version and ACTIVE status. Observed postcondition drift can repair the
expected destination. Interrupted, unverified and disconnected paths do not overwrite skills.

`AppSkillRecoveryTest` covers offline replay, suffix repair, failure isolation, shortest paths, evidence,
request-span inputs and JSON persistence. Opt-in `OpenAiLivePlannerTest` (`SONJU_LIVE_MODEL=1`) sends
only synthetic Accessibility/Canvas screens to validate actual Luna planning and structured responses.
The debug build reads `OPENAI_API_KEY` from ignored `local.properties`; release does not embed the key.

Model contract reference: https://developers.openai.com/api/docs/models/gpt-5.6-luna

### 실기기 확인 (2026-09-06, Samsung SM-F731N)

- 설정의 한 손 조작 모드 화면까지 AI가 이동하고 `goal verified`를 기록했다. 성공 경로는 5단계로 저장됐다.
- 같은 경로의 검색 화면 postcondition 불일치 후 AI가 목표를 완료했고, 같은 skill ID의 실패 지점 이후 경로를 수선해 버전 1 → 2 → 3, ACTIVE로 저장했다.
- Galaxy AI 화면은 최초 탐색 후 2단계로 저장됐다. 재실행에서 `goal verified source=SKILL_FAST_PATH tools=2 modelCalls=0`을 확인했고, 종료 후 UI 트리의 `Galaxy AI` 제목과 `com.android.settings/.SubSettings` activity를 확인했다.
- 반복된 접근성 실패 후 실제 화면을 사용하는 VLM 호출 전환을 확인했다. 좌표 추론 자체의 실제 API 검사는 합성 Canvas 이미지로 수행했으며, 모든 실제 앱의 좌표 실행 성공을 입증한 것은 아니다.
- 최종 기본 JVM 검사: 214개 중 212개 통과, opt-in API 검사 2개 제외. 해당 API 검사 2개는 별도 실행에서 통과했다.
- 삼성 검색 화면처럼 부가 UI가 변하는 경로는 엄격한 fingerprint 검증에 따라 AI 복구가 다시 발생할 수 있다. 모든 명령의 무조건 완료나 모든 재실행의 AI 호출 0회를 보장하지 않는다.

### 병원 예약 기록 조회와 사용자 로그인 대기

`분당서울대병원 예약 기록 알려줘.`는 현재 화면 설명이 아닌 공식 모바일 웹 조회 요청으로 분류한다.
검토한 공식 링크 `https://www.snubh.org/personal/resvrStatusList.do`를 `OPEN_URL` 계획으로 열고,
verifier와 실행 후 브라우저 주소 검증을 거친다. 이 조회 요청에서는 입력·클릭·예약 변경·취소를 자동 실행하지 않는다.

공식 브라우저 주소와 로그인 UI를 함께 확인하면 `WAIT_FOR_USER`로 중단한다. 이 단계는 접근성
실행 권한을 발급받을 수 없으며, 모델이 제안할 수 있는 도구에서도 제외된다. 로그인 방법을 안내하고
원래 요청을 유지한다. 공통 사용자 개입 흐름이 완료 화면을 안정적으로 확인하면 자동 재개한다.
패널 바깥을 눌러 로그인 화면을 조작할 수 있고, 손주를 다시 열어
`완료했어요 · 계속`을 누르거나 `로그인 완료`라고 말해 공식 사이트의 로그인 상태를 재확인할 수도 있다.
실패한 로그인, 다른 사이트, 로그인 화면의 재표시는 완료로 처리하지 않는다.

같은 서비스 세션에서는 로그인 대기 시간을 3분 실행 예산에서 제외하고 실행 이력을 보존한다.
서비스가 재시작되어도 요청과 복귀할 앱·origin·해시만 최대 24시간 기기에 남겨 재개할 수 있다. 재시작 전 UI와 실행
이력은 복원하거나 꾸며내지 않는다. 명시적인 중단이나 다른 요청은 보관된 로그인 요청을 지운다.

로그인 후 조회 결과(예약 행의 날짜·시간·진료과 또는 명시적인 예약 없음)를 실제 화면에서
확인한 뒤에만 완료 및 AppSkill 저장을 허용한다. 현재 화면의 조회 결과는 기기 UI에서 전달하며
모델 계획이나 피드백 기록에 넣지 않는다. 스킬에는 공식 URL, 사용자 로그인 체크포인트와 화면
fingerprint만 남는다. 비밀번호·인증번호 입력 동작이나 예약 행의 내용은 저장하지 않는다.
현재 보이는 결과와 전체 예약 이력은 구분하며, 로그인 후 실제 페이지 구조·다중 페이지 범위는 실기기 검증이 필요하다.

`HospitalReservationWorkflowTest`는 요청 분류, 주소 위장 차단, 대기 예산, 잘못된 재개 차단,
조회 완료 근거, 사용자 체크포인트 보존, JSON에 인증·예약 내용이 남지 않음, 모델 호출 없는
합성 경로 재사용을 검증한다. 실제 계정 로그인 후 조회·저장 검증을 대신하는 테스트는 아니다.

2026-09-07 검증: JVM 220개 중 218개 통과, opt-in API 검사 2개 제외. `lintDebug`와
`assembleDebug` 통과 후 Samsung SM-F731N에 업데이트 설치했다. 실제 계정의 로그인·예약 조회와
AppSkill 저장 여부는 사용자 로그인 후 확인할 항목으로 남아 있다.

#### 삼성 인터넷 중단 원인과 수정 (2026-09-07)

실행 로그에서 01:16:47~01:17:05 동안 `OPEN_URL` 4회와 `POSTCONDITION_TIMEOUT`을 확인했다.
실제 화면은 이미 병원 로그인 페이지였지만, 삼성 인터넷 주소 노드는 `U+200E + snubh.org`만
제공했고 웹 본문은 접근성 트리에 없었다. URL 파싱 실패와 본문 부재 때문에 도착 확인이 실패했다.
실패한 URL 열기를 다시 시도한 뒤 반복 감지로 중단했으나, 기존 공통 메시지가 이를 3분 제한으로 표시했다.

주소 가장자리의 표시용 방향 문자만 제거하고 내부 호스트 문자는 그대로 검증한다. 공식 도메인
도착과 조회 완료를 구분하며, 실패 여부와 관계없이 로그인 전·후 각 구간에서 URL 열기를 한 번만
시도한다. 반복 감지·횟수·실행 시간·로그인 대기의 중단 사유를 따로 표시한다.

공식 도메인은 확인했지만 웹 본문을 읽지 못하면 번들 한국어 ML Kit OCR로 기기에서 화면 글자를
읽는다. 이 텍스트는 `localReadOnlyText`에만 두며, 실행 가능한 `UiElement`, 모델용 compact text,
스킬 저장 값에 포함하지 않는다. 캡처 세대와 화면 epoch가 달라진 결과는 버리고 재시도 횟수와
인식 대기 시간을 제한한다. 로그인 UI와 실제 조회 결과는 별도의 로컬 조건으로 검사한다.

검증: 기본 JVM 223개 중 221개 통과, opt-in API 검사 2개 제외. Samsung SM-F731N의 실제 빈
로그인 화면 캡처를 사용한 `HospitalPageReadDeviceTest` 1개도 기기에서 통과했다. 해당 기기 검사는
화면 인식과 로그인 판정을 검증하며, 실제 계정 로그인이나 전체 예약 기록 조회를 실행하지 않는다.

OCR API reference: https://developers.google.com/ml-kit/vision/text-recognition/v2/android

#### 로그인 후 빈 예약현황 판정 보완 (2026-09-07)

실제 기기 로그에서 사용자 로그인 대기와 `user login resumed originalGoal=true`를 확인했다.
로그인 후 예약현황에는 “진료예약현황이 존재하지 않습니다.”가 표시되었으나, 기존 빈 결과
판정식은 “예약 내역이 없습니다.” 형태만 인식해 재관찰 후 중단했다. 진료·검사 접두어와
“존재하지 않습니다”를 지원하고, “조회할 수 없습니다” 같은 읽기 오류는 빈 결과에서 제외한다.
공식 브라우저 주소, 조회 제목, 로그인 상태 확인 조건은 유지한다. 웹 본문을 접근성으로
관찰할 수 없는 병원 페이지는 기존 로딩 재시도 한도(5회)를 사용한다.

실제 캡처를 로컬 OCR로 읽고 완료 조건을 평가하는 기기 검사를 추가했다. 캡처는 앱 빌드
진단 경로와 일시적인 기기 비공개 캐시에만 두며, 테스트 소스에는 개인 정보 없는 문구만 쓴다.
앞선 중단 실행에는 완료 증거가 없으므로 병원 AppSkill 저장은 확인되지 않았다. 캡처 인식
검사와 사용자가 새 명령을 실행해 결과 안내·스킬 저장까지 마치는 검증은 구분한다.

최종 검증: JVM 224개 중 222개 통과, opt-in API 검사 2개 제외. 병렬 빌드 중 기존
UiTreeReader 성능 테스트가 3.301초로 3초 한도를 초과했으며, 코드·기준 변경 없이
전체 테스트를 단독 실행해 해당 검사가 0.474초에 통과했다. `lintDebug`와
`assembleDebug`도 통과했다. 최종 APK의 실제 결과 캡처 기기 검사 1개는 0.442초에
통과했으며, 업데이트 설치 시각은 2026-09-07 01:46:26이다. 테스트 APK와 기기 캐시의
캡처는 제거하고 손주 접근성 서비스가 다시 등록된 것을 확인했다. 실제 명령 재실행의
완료·AppSkill 저장·재사용 검증은 별도로 남아 있다.

#### 서비스 캡처 경로의 OCR 손실 재현 (2026-09-07)

01:50 실행에서 브라우저 노드는 32개, OCR은 21줄이었으나 결과 판정은 계속 false였다.
동일한 실제 화면을 원본 PNG로 읽으면 성공하고, 서비스에서 사용하던 JPEG 품질 82로
변환한 뒤 읽으면 실패하는 현상을 기기 검사에서 재현했다. 따라서 캡처 파일만 검사한
이전 검증은 서비스 캡처 경로의 손실 압축 차이를 포함하지 못했다.

로컬 페이지 읽기는 `captureScreenshotBitmap`의 원본 해상도 비트맵을 OCR에 직접 전달한다.
다른 시각 모델 경로에서 필요한 JPEG 인코딩은 `captureScreenshotFrame`에서 수행한다.
원본 화면과 읽은 내용은 기기 내에서만 처리하며, 완료 조건을 느슨하게 바꾸지 않는다.

디버그 APK의 서비스 dump에 `--read-hospital-page`를 추가했다. 이미 열린 공식 사이트의
실제 접근성 트리와 서비스 캡처·OCR·목표 판정을 읽기 전용으로 검사하고, 개수·불리언
진단만 SonjuFlow에 남긴다. 명령 실행, URL 열기, 클릭, 스킬 저장은 수행하지 않는다.
Android instrumentation은 이 기기에서 대상 프로세스 재시작 후 손주 서비스에 다시 바인딩되지
않았으므로, 실제 서비스 경로 검사는 해당 dump 진단으로 수행한다.

검증: JVM 224개 중 222개 통과, opt-in API 2개 제외, lint 오류 0개, APK·테스트 APK
빌드 통과. 원본 로그인·빈 예약현황 판정 및 JPEG 손실 재현 기기 검사 3개가 통과했다.
JPEG OCR 오독은 “진료예약헌현황이 존재하지 않습니다.”로 확인했다. 원본 화면 판정은
통과하지만 이 오독을 정상 데이터로 추정해 교정하거나 완료 조건에 추가하지 않는다.

기기 검사 뒤 테스트 APK와 비공개 캐시의 캡처를 제거했다. instrumentation 종료로
접근성 서비스가 Crashed services에 남는 현상은 앱 재설치 후 실제 서비스 PID와 dump
호출로 복구를 확인했다. 최종 업데이트 설치 시각은 2026-09-07 02:02:37이다.
이후 live probe는 잠긴 휴대폰에서 실행되어 read=false/goal=false였고 작업은 실행하지
않았다. 잠금 해제 후 실제 브라우저 페이지의 서비스 경로 검증은 계속 진행할 항목이다.

#### 실제 로그인 재개·조회 완료·스킬 저장 확인 (2026-09-07 21:11)

사용자 재실행 로그에서 21:10:26 로그인 대기, 21:10:48 동일 목표 재개,
21:10:51 원본 비트맵 OCR의 result=true, 21:10:52 `goal verified source=LOCAL_RULE
tools=3 modelCalls=0`를 확인했다. 이어 21:11:38 실행 중인 서비스의 읽기 전용 probe도
nodes=32, truncated=false, read=true, goal=true, actionsDispatched=0을 기록했다.

기기 로컬 저장소에는 병원 AppSkill 1개가 ACTIVE, version=1, success_count=1,
failure_count=0으로 저장되었다. 실제 실행 이력은 공식 예약현황 열기 → 사용자 로그인
체크포인트 → 공식 예약현황 열기의 3단계다. 이 확인은 실제 최초 실행의 완료와 저장을
입증하며, 저장된 스킬을 사용한 후속 재실행까지 입증한 것은 아니다.

#### 요청 목적 분리와 서울아산병원 조회 (2026-09-07)

`RequestInterpreter`는 문장의 대상과 실행 표현을 보고 현재 화면 설명, 사용법 안내,
정보 조회, 동작 요청을 구분한다. `알려줘`, `어디`, `언제`만으로 화면 설명 경로를
선택하지 않는다. 불명확한 문장은 기존 Luna 계획기로 보내 문맥과 원하는 결과를
해석한다. 예를 들어 `내일 날씨 알려줘`는 예보 조회, `이 화면 내용 알려줘`는 화면
설명, `조회 방법 알려줘`는 안내, `조회 방법을 검색해서 알려줘`는 검색 후 정보 전달이다.
일반 정보 조회는 학습된 도착 화면 지문이나 검색어 노출만으로 완료하지 않는다.
계획기는 실제 조회 결과를 완료 근거로 지정하고 관찰한 값을 응답에 담아야 한다.
이는 의미 해석의 완전성을 보장하는 규칙이 아니며, 모델의 결과 해석에는 오류가 남을 수 있다.

병원 예약 조회 의도와 병원 식별을 분리했다. 서울아산병원은 공식 예약현황 URL
`https://www.amc.seoul.kr/asan/mychart/medical/appointment/medicalAppointmentList.do`로
연결한다. 로그인 대기·재개와 최종 결과는 사용자가 요청한 병원의 브라우저 주소로
검증한다. 다른 병원의 로그인/결과 화면으로 재개하거나 완료할 수 없다. 새로운 예약,
취소·변경 요청을 읽기 전용 조회 경로로 처리하지 않으며, 검증되지 않은 병원이나 별도
검사/취소 기록 범위는 그 한계를 안내한다. 기존 AppSkill 요청 해시와 작업 식별 방식은
유지하여 이미 저장된 스킬을 무효화하지 않는다.

회귀 검증에는 다양한 정보/설명/사용법 표현, 예약해 둔 내역과 새 예약 명령의 구분,
서울아산병원 로그인 대기·동일 목표 재개·결과 확인·스킬 저장/검색 및 병원 간 혼동 방지가
포함된다. 합성 화면 테스트는 실제 서울아산병원 계정의 로그인과 예약 조회 완료를
입증하지 않으며, 실제 기기에서 사용자 실행 후 별도로 확인해야 한다.

검증 결과: 단위 테스트 230개 중 227개 통과, 선택 실행 API 테스트 3개 제외.
이후 `SONJU_LIVE_MODEL=1`로 일반 정보 조회 테스트를 별도 실행하여 1개 통과/제외 0개를
확인했다. 실제 `gpt-5.6-luna`가 합성 예보 화면의 조회 버튼을 선택한 뒤, 다음 관찰의
19도/27도와 맑음 정보를 완료 응답에 담고 현재 결과에 일치하는 근거를 반환했다.
`lintDebug` 오류 0개, `assembleDebug` 성공. 기기 `SM-F731N`에 수정 APK 설치 성공,
패키지 갱신 시각 2026-09-07 21:39:20 및 접근성 서비스 활성화를 확인했다.

#### Generic installed-app discovery (2026-09-07)

Both planner entry points now supply `InstalledApps.query(packageManager)` to the shared
`OpenAiPlanner`. Accessibility, semantic-map and screenshot plans receive the same complete
launcher catalog of labels and exact packages. The manifest already declares MAIN/LAUNCHER
visibility; no app/category list or broad package permission was added. App data is observation,
not instructions, and a missing catalog is explicitly unknown. Each query is fresh, so installs
and removals are not hidden by a service-lifetime cache. Exact package launches never fall back
to another app with a similar label. When several apps are relevant, the planner identifies the
chosen source and must not present its results as covering every app.

The verifier recognizes history/list/detail navigation from the grounded UI label, rather than
mistaking the action named in a record for a commit. A conservative standalone lookup grammar
allows a risk-keyword subject to complete using existing observed goal checks; mixed action
requests retain their previous completion boundary. These rules contain no app or domain names.
Commit buttons, sensitive inputs, ambiguous targets and unobserved completion remain guarded.

Regression checks: `OpenAiPlannerTest` covers 221 apps, unrelated request types, all plan modes,
fresh data, unavailable/empty catalogs and escaped labels. `InformationLookupTest` covers history
navigation, observed completion, stale/missing evidence and disguised commits. The opt-in
`InstalledAppsDeviceTest` checks real package visibility and model selection without reading
account data or executing a transaction.
Validation: 234 JVM tests, 231 passed / 3 opt-in network tests skipped; lint 0 errors
(80 warnings), debug and instrumentation APK builds passed. After the final prompt wording
change, the 12 focused planner/lookup tests passed again. Device catalog/model tests passed
2/2: Sonju observed 172 launchable apps and Luna selected `viva.republica.toss` for the
unnamed request. The test rejects reopening its empty observation app as lookup progress.

On SM-F731N, the installed build (updated 2026-09-07 22:19:01 KST, SHA-256
`1ABE847477FBA8E9A657FBD72F0BE35D30E8AE03DA976BED0AB0B54554E8CF4E`) processed
`송금 내역 알려줘`: OPEN_APP was verifier-approved, execution and package postcondition
passed at 22:23:36, and a fresh 142-node Toss observation followed. After the user restored
accessibility following instrumentation and personally completed biometric authentication,
the same request was resubmitted from the authenticated app. CLICK and SCROLL_DOWN succeeded;
at 22:31:06 the runtime logged `goal verified source=OPENAI_STRUCTURE tools=2 modelCalls=4`.
A post-completion UI capture confirmed `viva.republica.toss` and CashflowActivity. Personal
record contents were not added to these diagnostics. No transfer or other commit was executed.
This proves the observed app-selection and authenticated lookup path, not automatic login
resumption or coverage of every account/app/date range.

Device-test caveat: instrumentation force-stops the target app and may leave Android's
accessibility binding disconnected until the user toggles it. Avoid external UI-automation
dumps during an active Sonju run; observe the service's own trace and capture the UI after
completion so the validation itself does not interrupt the accessibility workflow.

### 공통 사용자 개입 대기와 자동 재개 (2026-09-07)

`UserIntervention`이 로그인, 생체인식, 캡챠, 본인인증, 2단계 인증과 민감 입력을 로컬에서
구분한다. `UiTreeReader`는 입력값을 분류에 사용하지 않고, 안내문에서 얻은 종류만 가림 처리 후
`UiSnapshot.userIntervention`에 남긴다. 특정 금융 앱이나 카테고리에 대한 예외는 없다.
기존 병원 조회의 공식 origin 및 결과 근거 검사는 그대로 유지한다.

`AutonomySession.pauseForUser` / `resumeAfterUser`가 모두 `WAIT_FOR_USER` 체크포인트를 사용한다.
Activity 진입도 서비스의 같은 경로로 넘긴다. 대기 중에는 모델·접근성 실행을 멈추고 로컬 화면만
관찰한다. 안내 패널은 시간만으로 접히지 않는다. 사용자가 바깥을 눌러 접거나 인증을 완료하면 작업이 이어진다. 패널을 다시 열어
‘완료했어요 · 계속’ 버튼 또는 ‘인증 완료’, ‘계속’ 음성으로 재검증할 수 있다.

자동 재개에는 인증 UI 소멸, 원래 앱(브라우저는 같은 검증된 HTTPS origin), 변경된 화면,
사용 가능한 내용과 조작 요소, 최소 1.2초의 안정 관찰이 필요하다. 로그인 → 캡챠 → OTP처럼
인증이 이어지면 계속 대기한다. 로딩·빈 화면·잘린 트리·다른 앱/사이트·잠금 화면은 자동 재개하지 않는다.
생체인식을 취소해 직전 화면으로 돌아온 경우도 자동 재개하지 않는다. 재개는 새 관찰·계획·verifier
검사를 다시 거치며, 인증 화면 복귀만으로 원래 조회 목표를 완료 처리하지 않는다.

`PendingUserInterventionStore`는 기존 `pending_user_login` 저장소를 이어 사용한다. 목표·인증 종류·
복귀 앱/origin·화면 해시만 최대 24시간 남기고, 민감값이 포함된 목표는 메모리에서만 대기한다.
기존 병원 로그인 목표는 공식 origin 검사를 유지해 이관한다. 서비스 재시작 후에는 자동 동작하지 않고
명시적인 계속 요청과 새 화면 검증이 필요하다. 중단·다른 명령·만료는 대기를 해제한다.
비밀번호·OTP·캡챠 정답과 사람이 수행한 입력/클릭은 실행 이력이나 AppSkill에 넣지 않는다.
검증 기록 (2026-09-08): 전체 JVM 테스트 245개 중 242개 통과, opt-in 3개 제외.
린트 오류 0개(기존 경고 80개), debug APK 빌드 통과. Android 기기 테스트 2개는
가림 처리 전 OTP 안내 분류와 최소 대기 상태 저장·복원·만료·삭제를 확인했다.

Samsung SM-F731N에서는 실제 안내 ‘생체 정보로 인증해주세요’, ‘지문을 입력하세요.’를
공통 생체인식 표현으로 처리했다. 패키지명 분기는 추가하지 않았다. 토스가 열린 상태의
‘송금 내역 알려줘’ 실행에서 00:08:55 클릭 postcondition=true → 00:08:56.202
`user intervention paused kind=BIOMETRIC` → 00:09:04.770
`user intervention resumed automatic=true originalGoal=true` → 00:09:27.850
`goal verified source=OPENAI_STRUCTURE tools=5 modelCalls=5`를 확인했다.
대기 중 모델 호출은 없었고, 대기 이후 원래 명령을 다시 전달하지 않았다.
종료 후 활동은 `CashflowActivity`, 후속 UI 트리는 토스 노드 45개로 확인했고,
대기 저장소가 비워졌음을 확인했다. 실제 송금이나 금융 확정 동작은 실행하지 않았다.

최종 APK SHA-256: `F35E9C2BE761E68D3534600B5A3645BE2A2D8656832E63C8CA63B4AEB61901C2`.
해당 APK를 기기에 설치하고 기존에 허용된 손주 접근성 서비스만 재연결했다.
실기기 자동 재개 근거는 위 생체인식 시나리오이며, 캡챠·본인인증·2단계 인증·외부 인증 사이트
복귀는 회귀 테스트 범위다. 초기 앱 선택에서 APP_NOT_INSTALLED, 조회 클릭에서 정책 차단 또는
노드 재검증 실패가 나온 시도도 있었다. 이 기록은 앱 선택/조회 진입의 모든 시도를 성공했다는
근거로 사용하지 않으며, 해당 경로의 간헐적 실패를 이번 인증 대기 수정으로 해결했다고 주장하지 않는다.

### 안내창 표시 시간 수정 (2026-09-08)

사용자 개입 안내의 4초 자동 접힘, 일반 안내의 3초 자동 닫힘, 완료/실패 피드백의
5초 자동 닫힘, 피드백 저장 후 1.2초 자동 닫힘을 제거했다. 화면 설명도 TTS 종료나
오류만으로 닫지 않는다. 안내 표시 시 남아 있는 음성 입력 타임아웃과 확정 콜백을
취소하며, 닫기·다시 말하기·계속 버튼과 인증 완료 후 자동 재개는 유지한다.

`:app:assembleDebug :app:lintDebug` 성공(린트 오류 0, 경고 80).
설치한 APK SHA-256: `48E94F9481A81E14C1F7213D4C99D380BC7A1FF1EF7B2B5E5E5EC24871553025`.
실기기 설정 화면에서 화면 설명 안내가 30초 이상 유지되는 것을 화면 캡처로 확인했고,
닫기 버튼 선택 후 안내창이 제거되고 작은 실행 버튼만 남는 것을 확인했다.
이번 표시 시간 수정 후 생체인식 시나리오 자체는 재실행하지 않았다.

### 요청 처리 중 터치 차단과 오로라 유지 (2026-09-08)

음성 요청 확정부터 요청 전체의 종료까지 `commandControlActive`로 오로라의 수명을 관리한다.
개별 실행의 종료, 재계획 대기, 모델 응답 대기, 화면 캡처는 오로라를 제거하지 않는다.
오로라 창이 앱 화면의 터치를 소비하며, 기존 중단 버튼에 대한 터치만 전달한다.
제목은 ‘손주가 작업 중이에요 · 화면 잠금’으로 표시한다. 완료/실패 안내, 중단,
명시적 확인 대기, 생체인식 등 사용자 개입 대기, 서비스 종료에서는 차단을 해제한다.
사용자 확인 후 실행하거나 인증 후 자동 재개하면 다시 적용한다.

제약: 이는 앱 화면 오버레이 방식으로, 상태 표시줄·시스템 탐색·하드웨어 버튼을 잠그는
기기 관리자 잠금 기능은 아니다. 아래 설명은 9월 8일 구현이며, 9월 9일 최소 시작점
허용 방식으로 대체했다. 당시 Android `dispatchGesture`의 좌표 터치/스와이프를
대상 앱에 전달하는 동안에는 오로라를 유지하되 `FLAG_NOT_TOUCHABLE`을 잠시 적용한다.
따라서 이 구간의 동시 사용자 터치를 완전히 차단한다고 보장하지 않는다.
현재 준비 지연은 120ms, 터치는 80ms, 스와이프는 320ms이며 콜백에서 즉시 차단을 복원한다.
예약된 제스처는 실행 세대가 바뀌면 취소한다. 화면 캡처에는 연한 오로라가 포함될 수 있다.

실기기에서 00:34:22.950 차단막 표시 후 화면 이동·재계획·접근성/시각 모델 대기를 거치는
동안 제거 로그가 없었으며, 00:34:26.788 일반 터치를 차단했다. 00:34:58.483 중단 버튼으로
차단막을 해제하고 중단 안내를 확인했다. 별도 ‘디스플레이 설정 열어줘’ 요청은
00:35:42.147 실행 성공/postcondition=true 후 00:35:42.190 차단막을 해제했다.
소프트웨어 정보 조회 자체의 완료를 이 검증으로 주장하지 않는다.

최종 제목/취소 검사 보완 후 `testDebugUnitTest assembleDebug lintDebug` 재통과
(242개 통과, 3개 건너뜀, 린트 오류 0/기존 경고 80).
설치 APK SHA-256: `DF458C53FB0AFC69FFDEDD8A836D7118CF8E40E45087D5815E4549217E606ACD`.
접근성 재연결 후 00:40:33.082 차단막 표시, 00:40:35.985 일반 터치 차단,
00:41:00.017 중단에 따른 해제를 재확인했고, 화면 잠금 제목과 오로라를 캡처로 확인했다.
생체인식 대기/자동 재개는 이번 수정 후 실기기에서 재실행하지 않았다.

### 자동 조작 중 최소 시작점만 터치 허용 (2026-09-09)

사용자가 선택한 ‘자동 조작 우선’에 따라 좌표 제스처에도 화면 전체를 열지 않는다.
`GestureTouchGuard`는 기존 오로라 위에 최대 4개의 투명한 입력 차단 창을 배치하고,
제스처 시작점 주변 반경 2dp의 작은 사각형만 남긴다. 주변 창의 실제 화면 좌표와
크기를 확인한 다음 오로라의 입력만 통과시킨다. 스와이프는 시작점에서 받은 앱이
이후 MOVE/UP을 계속 받으므로 이동 경로 전체를 열 필요가 없다.

오로라 창은 계속 붙어 있으며, 제스처 완료/취소 시 전체 차단을 복원한 뒤 주변 창을
제거한다. 창 변경은 pre-draw 후 추가 120ms 동안 반영을 기다린다. 실제 기기에서
이 대기 없이 접근성 제스처를 전송하면 이전 차단막이 DOWN을 받는 현상을 확인했다.
차단 창은 화면 절대 좌표로 배치하며, 상태 표시줄 높이를 중복해서 빼지 않는다.
창 배치 불일치·화면 revision 변경·요청 세대 변경은 제스처를 취소하고, 2초 내 창
전환이 끝나지 않으면 요청을 중단한다. 차단막 준비 실패 시 작업도 시작하지 않는다.
중단/접근성 서비스 interrupt는 모델 요청과 대기 중인 실행도 취소한다.

기기 테스트 2개 통과(Samsung SM-F731N, Android API 36): 주변 영역의 터치 차단,
작은 시작점에서 출발해 영역 밖으로 이동하는 스와이프의 앱 DOWN/MOVE/UP 수신,
실제 `AccessibilityService.dispatchGesture` 성공, 오로라 유지 및 전체 차단 복원을
확인했다. 기기 테스트 전용 Activity는 debug에만 포함되고 외부에 공개하지 않는다.
JVM 테스트는 248개 중 245개 통과, opt-in 3개 제외다.
최종 `assembleDebug assembleDebugAndroidTest lintDebug` 성공(린트 오류 0, 기존 경고 80).

일반 요청 ‘디스플레이 설정 열어줘’에서는 00:16:51.559 차단막 표시 후
로컬 실행의 postcondition timeout과 재계획/모델 대기 동안 유지되었고,
00:17:05.276 `goal verified source=OPENAI_STRUCTURE tools=1 modelCalls=1` 후
00:17:05.306 차단막을 해제했다. 종료 화면은 `Settings$DisplaySettingsActivity`였다.
따라서 이 시도는 로컬 실행 검증 단독 성공으로 기록하지 않는다.

설치 APK SHA-256: `95D54080157437F67BBDBB607ECFF5A991DE2CF269F5800E6C328502609F6F24`.
시스템 영역/하드웨어 버튼은 차단 범위 밖이며, 작은 시작점이 열린 동안 그 점을
사용자가 동시에 누르는 경우까지 구별할 수는 없다. 인증 대기와 자동 재개 시나리오는
이번 변경 후 실기기에서 다시 실행하지 않았다.

### 불필요한 이미지 분석 호출 제한 (2026-09-09)

기존 `visualFallbackActive`는 일반 재계획 실패를 2번 누적하면 요청이 끝날 때까지
유지되었고, 이미지 시도 제한도 사용자 요청이 아닌 재계획 generation 기준이었다.
따라서 완료 근거 불일치·오래된 AppSkill·후속 화면에서도 이미지 분석으로 쉽게 넘어갔다.

이제 현재 의미 화면에서 접근성 구조 기반 모델을 먼저 사용하고, 실제 대상 누락 또는
노드 실행 거부가 2번 누적된 경우에만 이미지 분석을 허용한다. 구조 모델이 실행 가능한
대상을 전혀 제시하지 못한 응답도 대상 탐색 실패에 포함한다. 낮은 계획 신뢰도,
완료 검증 실패, 저장 경로 불일치, postcondition timeout, 제스처 실패는 이 횟수에
포함하지 않는다. 검증기의 실제 GroundingResult.NotFound만 명시적으로 분류한다.

화면이 바뀌거나 WAIT 이외의 동작이 성공하면 탐색 실패와 구조 모델 시도 상태를
초기화한다. 이미지 시도는 동일 의미 화면당 1회, 사용자 요청 전체에서 최대 2회이며
같은 화면을 재방문해도 중복 전송하지 않는다. 로딩 중·잘린 트리는 이미지 분석을
보류하고 기존 재관찰 경로를 따른다. 민감 화면의 원격 스크린샷 금지와 좌표 동작의
현재 프레임 검증은 유지한다. 시각 완료 근거는 해당 화면에서 이미지 시도를 한 경우에만
인정하며, 단순히 탐색 실패가 누적되었다는 이유로 인정하지 않는다.

회귀 테스트 6개를 추가해 일반 실패 제외, 구조 모델 우선, 화면 전환/성공 시 초기화,
화면 재방문/재계획 시 이미지 중복 방지, 요청당 예산, 검증기 실패 분류를 확인했다.
JVM 테스트 254개 중 251개 통과, opt-in 3개 제외다. 실제 ‘와이파이 설정 열어줘’는
00:26:15.064 `LOCAL_RULE / OPEN_WIFI_SETTINGS` 허용 → 00:26:16.266
`success=true postcondition=true` → 00:26:16.303 차단 해제로 완료했고, 모델 호출은 없었다.
이 단일 로컬 요청을 전체 사용량의 토큰 절감률 근거로 사용하지 않는다.
설치 APK SHA-256: `CA39F41A2CCCBC0A00F22C195F95F02542AF74E1E7313254D4BF23F658A1FE93`.
최종 `testDebugUnitTest assembleDebug lintDebug` 성공(린트 오류 0, 기존 경고 80).
