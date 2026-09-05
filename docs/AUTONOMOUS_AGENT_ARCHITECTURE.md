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

세션 상한은 도구 24개와 3분입니다. 같은 화면에서 같은 action signature가 두 번 실패하면 다음 프롬프트의 변경 필수 목록에 들어갑니다. 사용자가 중단하면 진행 중인 네트워크 요청, gesture, 실행 generation을 무효화합니다.

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
| `OPEN_APP` | 설치 앱 label/alias를 package로 확인한 뒤 launcher intent | 앱 package 재관찰 |
| `CLICK` | 유일한 selector를 clickable node/ancestor로 해석해 `ACTION_CLICK` | 실패 결과를 재계획에 전달 |
| `CLICK_COORDINATE` | 0~1 좌표를 display 좌표로 변환해 `dispatchGesture` | live revision과 좌표 범위 확인 |
| `SET_TEXT` | 유일한 editable node에 focus 후 `ACTION_SET_TEXT` | 값 길이 4,000자 제한 |
| 방향 스크롤 | node가 노출한 방향 action | 중앙 swipe gesture |
| `BACK/HOME` | accessibility global action | 새 package/화면 관찰 |
| 알림/빠른 설정 | accessibility global action | System UI package 관찰 |
| `WAIT` | 100~2,000ms bounded wait | 도구 예산에 포함 |

접근성 selector는 path, 전체/suffix view ID, 정확한 text/description/hint, 정규화된 부분 일치 순으로 점수를 줍니다. 최고 점수 후보가 둘이면 임의로 첫 번째를 누르지 않습니다.

## 좌표와 시각 폴백

접근성 구조에 실행 단서가 없고 화면이 비민감일 때만 Android 11 이상의 원본 캡처를 시각 모델에 보냅니다. 반환 좌표는 직접 실행하지 않고 `CLICK_COORDINATE`가 포함된 정식 계획으로 변환합니다. 따라서 좌표 탭도 세션 이력, 사용자 확인, live revision, 도구 예산, 성공 경로 학습을 동일하게 거칩니다.

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
