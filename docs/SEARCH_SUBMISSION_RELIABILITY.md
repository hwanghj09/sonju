# 검색어 입력과 실제 검색 제출

2026-09-14 검색 이후 주문 준비가 멈추는 현상의 후속 분석·수정은 [작업 진행 검증 기록](WORKFLOW_CONTINUATION_RELIABILITY.md)에 정리했다. 아래는 검색 제출 수정 당시의 기록이다.

2026-09-13. 검색어가 입력창에 표시된 것과 검색 요청이 제출된 것은 별개다.

## 재현한 원인

삼성 SM-F731N, Android API 37, 배달의민족 16.22.0에서 `배달의민족에서 치킨 검색해줘`를 실행했다.

1. `SET_TEXT`는 성공했고 입력값 `치킨`도 확인됐다.
2. 입력 후 검색창은 `android.widget.EditText`, editable=true, `SET_TEXT, IME_ENTER`를 제공했지만 view ID, hint, content description, IME action label은 비어 있었다. `검색어 지우기`는 입력창의 **자식 노드**에 있었다. 실제 입력기의 옵션은 `0x02000003`(SEARCH), custom actionId는 0이었다.
3. 기존 `SearchSubmissionPolicy`는 입력창 자신의 ID·힌트·설명·pane title만 검사했다. 따라서 올바른 검색창을 거부했다. 모델이 제출을 제안해도 `NeedsReplan`이 반복됐고, 105.6초 뒤 시험을 중단할 때도 자동완성 화면이었다. 키 입력 API에 도달하기 전 검증 단계의 실패다.
4. 검색창 판별을 고친 첫 중간 APK에서는 모델이 같은 자동완성 화면을 결과로 오인해 **동작 0회**로 완료를 제안했다. 외부 검사의 최소 실행 횟수 조건으로 실패를 확인했고, 완료 판정도 수정했다.
5. 실제 Chrome 검색에서는 웹 검색창의 힌트·경로가 바뀌어 입력 후 연결이 실패했고, 검색 버튼 선택 후 결과가 열렸어도 일반 주소창을 인증 입력으로 오인해 멈췄다. 읽기 전용 진단에서 Chrome 소유 `url_bar`가 password=false이고, 긴 숫자가 포함된 506자 추적 URL인 것을 확인했다. 민감정보 가림으로 숨겨진 주소를 `UserIntervention.required`가 OTHER로 판단한 것이다. 원문 URL은 진단 결과에 저장하지 않았다(`search-submission-qa/chrome-editor-metadata.json`).
6. 후속 Chrome 관찰은 보이는 노드 86개인데도 경로 깊이 32에서 잘렸다. 웹 페이지의 깊은 접근성 계층이 기존 깊이 상한에 걸려 모델의 입력 계획 전체가 거부됐다. 깊이 상한을 128로 조정하고, 전체 2,000노드 제한과 취소 검사는 유지했다. 40개 의미 있는 그룹 안의 실제 WebView 검색창을 별도 기기 회귀에 추가했다. 잘린 관찰을 실행 가능하다고 허용하는 우회는 하지 않았다.
7. 중간 Chrome 검사(`chrome-busan-final`)는 실행 로그상 제출에 성공했지만, 화면 QA에서 모델이 이후 검색창을 다시 열고 자동완성 후보를 결과로 설명한 것을 발견했다. 해당 기록은 최종 성공 근거에서 제외했다. 제출 직후 같은 값의 입력창을 다시 여는 불필요한 CLICK을 차단하고, 입력창을 다시 열었다면 이전 제출 이력으로 목표 완료를 인정하지 않도록 했다. 새 값을 입력하는 SET_TEXT는 계속 허용한다.

근거: `app/build/reports/completion-qa/baemin-search-before/`의 `flow.log`, `screen.xml`, `screen.png`, `editor-diagnostic.xml`; 중간 APK의 잘못된 완료는 `baemin-search-after-first/`에 보존했다.

## 공통 수정

- 검색창 자체의 의미와 그 입력창에 속한 자식 노드의 검색 라벨을 함께 검사한다. 다른 입력창의 자식, 화면 다른 곳의 검색 제목, 숨겨진 노드를 근거로 허용하지 않는다.
- Android 13 이상에서는 현재 앱·포커스·전체 입력값이 접근성 노드와 일치하는 InputConnection의 실제 IME 옵션도 관찰한다. SEARCH는 라벨이 없어도 검색 의미를 제공한다. 이 값은 모델이 지정하지 않으며 현재 관찰에만 연결한다.
- 입력 후 사라지는 placeholder는 같은 창·클래스·고유 ID 또는 같은 경로의 입력창으로 재연결한다. 웹 검색창처럼 힌트·경로가 함께 변하면 양쪽의 검색 의미와 유일한 후보를 확인한다. 다른 의미의 라벨, 다른 창, 중복 ID, 민감 필드는 허용하지 않는다.
- 이미 포커스된 입력창을 불필요하게 클릭하지 않는다. 포커스를 준비한 뒤 다시 관찰해, 실제 SEARCH/GO/DONE 또는 명시된 검색 custom actionId를 제출한다.
- 전달이 수락돼도 실제 변화가 없을 때만 같은 검색어와 입력창을 다시 검증하고 다음 방법을 시도한다. 실제 편집기 동작 → 노드의 접근성 IME 동작 → 제한된 Enter 키 → 관찰한 유일한 키보드 검색 버튼 순서이며 각 방법은 한 번만 사용한다. 취소·시간 제한·대상 변경 검사는 유지한다.
- Enter는 검증된 검색창의 일치하는 InputConnection에만 보낸다. SEND/NEXT/PREVIOUS와 여러 줄 입력의 줄바꿈은 검색으로 취급하지 않는다. 이전 Android에서는 실제 노드 동작과 관찰 가능한 키보드 버튼을 사용하며 셸 키 주입을 제품 기능으로 사용하지 않는다.
- 제출 직전 새 관찰을 기준으로 검색 효과를 검증한다. 커서, 포커스, 키보드에 따른 위치 변경, 노드 경로 변경, IME capability 변경만으로 성공 처리하지 않는다. 결과 내용 또는 주소 이동을 확인하고, 최종 목표는 별도로 검사한다.
- 검색창이 남아 있는 검색 과제에서 입력값·자동완성 후보만으로 완료하지 않는다. 실제 제출 또는 확인된 검색 실행 컨트롤 선택 이력, 명확한 결과 문맥, 별도 검증된 시각 근거가 필요하다. 이번 실행에서 입력값을 바꿨다면 이전 결과 페이지의 ‘검색결과’ 제목만으로 완료하지 않는다. 입력창과 그 자식만 가리키는 goal checks도 완료 근거가 아니다.
- 제출 후 같은 검색창을 다시 여는 동작은 결과 관찰로 재계획한다. 이미 입력창을 다시 연 이력이 있거나 처음부터 편집 모드이면 자동완성을 과거 결과 제목과 섞어 완료 근거로 사용하지 않는다.
- 기존 브라우저 소유 주소창 식별을 재사용해 URL의 가림 상태와 인증 요구를 구분한다. 주소는 계속 민감정보로 가리고 원격 스크린샷도 제한한다. 실제 로그인·인증 표시와 일반 민감 입력란의 사용자 개입 요구는 유지한다.

메시지·댓글·인증 필드 제한, 입력값 전체 일치, verifier-issued `VerifiedPlan`, 현재 화면 재검증은 유지한다. 배달의민족이나 Google 전용 우회는 추가하지 않았다.

## 플랫폼 계약과 추가 보강

Android의 [ACTION_IME_ENTER](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction#ACTION_IME_ENTER)는 `EditorInfo.actionId`를 사용하며 기본값은 UNSPECIFIED다. 키보드에 표시된 SEARCH/GO와 항상 같은 값이 아니다. [AccessibilityInputConnection.performEditorAction](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection#performEditorAction(int))은 반환값이 없는 전달 API이므로 실제 검색 성공의 증거가 아니다.

[SurroundingText.offset](https://developer.android.com/reference/android/view/inputmethod/SurroundingText#getOffset())은 -1이면 위치를 알 수 없다는 뜻이다. 현재 입력값 전체가 접근성 노드 및 연결의 주변 텍스트와 일치할 때 이 정상적인 반환값도 허용한다. 부분 값과 다른 값은 거부한다. 이는 플랫폼 계약에 따른 호환성 보강이며, 최초 배달의민족 실패 원인과 혼동하지 않는다.

## 회귀 검사

`SearchSubmissionTest`는 실제 배달의민족 형태, 입력창 자식의 소유 관계, 라벨 없는 SEARCH, 다른 IME 동작, 전체 값 비교, custom actionId, 줄바꿈 제한, 포커스·위치만 바뀐 화면, 자동완성의 잘못된 완료를 검사한다.

`SearchSubmissionDeviceTest`는 별도 테스트 APK의 네이티브 입력창과 로컬 WebView 폼을 **실제 검증기 → 서비스 실행기 → 사후조건**으로 실행한다. 일반 SEARCH, custom actionId, 라벨 없는 SEARCH, 수락 후 무효인 동작의 Enter 복구, 지연 제출의 중복 방지, 일반/깊은 WebView form submit, 모든 방법이 무효인 경우의 실패를 검사한다. 테스트 앱은 릴리스에 포함되지 않는다.

기기 시험 초기에는 instrumentation이 Sonju 프로세스를 재시작해 서비스가 연결되지 않았고, 별도 시험 앱에 Kotlin runtime이 포함되지 않아 fixture가 시작되지 않았다. 기존 서비스 재연결 도우미를 재사용하고 fixture를 Android 표준 API만 사용하는 Java로 바꿨다. 초기 실패 기록은 제품 동작 검증과 구분해 보존한다.

## 최종 검증 결과

| 검사 | 결과 | 근거 |
| --- | --- | --- |
| JVM 회귀 | 326개 중 320개 통과, 선택 실행 6개 제외, 실패/오류 0 | `app/build/test-results/testDebugUnitTest/` |
| 실기기 회귀 | 네이티브·일반/깊은 WebView 등 8개 모두 통과, 41.7초 | `search-submission-qa/device-release.txt` |
| 빌드·린트 | 앱/AndroidTest APK 생성 성공. 린트 오류 0, 기존 경고 81 | `app/build/reports/lint-results-debug.xml` |
| 최종 APK 배달의민족 | `배달의민족에서 피자 검색해줘`: SET_TEXT → SEARCH(3) → 가게 목록/최소주문/배달팁 확인, 25.8초. 도구 3회, 모델 3회 | `completion-qa/baemin-pizza-release/`의 로그·XML·화면 |
| 최종 APK Chrome | `크롬에서 경주 관광 검색해줘`: 입력·IME 검색·재관찰 후 실제 Google 지도/명소 결과 확인, 54.4초. 도구 5회, 모델 7회. 자동완성 화면이 아님을 화면 QA로 확인 | `completion-qa/chrome-gyeongju-release/`의 로그·XML·화면·`visual-review.json` |
| 원래 실패 명령 비교 | ‘치킨’ 입력 후 제출 거부가 반복되던 상태에서 실제 SEARCH와 치킨 가게 결과 확인까지 25.6초. 뒤이은 치킨 재검사도 30.9초에 성공. 이 두 기록은 마지막 완료 가드 보강 전의 비교 자료 | `completion-qa/baemin-chicken-verified/`, `baemin-chicken-final/` |
| 설치 확인 | 연결 기기에 설치 성공. 로컬 APK와 설치된 base.apk SHA-256 일치 | `search-submission-qa/verification.json` |

`completion-qa/`와 `search-submission-qa/`는 `app/build/reports/` 아래에 있다. 최종 APK SHA-256:

```text
dddb3831445e8eedef0f15253201af1a623c54ae13a3b47cd83d8a02969129f6
```

Chrome 명령에는 모델의 두 차례 SUBMIT_TEXT 제안이 포함된다. 각 제안은 새 관찰과 검증을 통과했으며, 최종 결과 화면을 확인한 뒤 완료했다. 모든 앱에서 첫 번째 제출만으로 끝난다는 보장은 아니다. Android 13 미만의 키보드 버튼 경로는 이번 실기기(API 37)에서 실행한 경로가 아니다.

## 검증 범위

이 수정은 특정 앱 전용 우회가 아니라 공통 관찰·판별·제출·완료 경로의 수정이다. 모든 앱·웹사이트·OS·앱 버전의 성공을 유한한 시험으로 보장할 수는 없다. 앱이 입력 연결, 접근성 동작, 실제 검색 버튼 또는 결과 근거를 제공하지 않으면 성공으로 꾸미지 않고 재관찰·재계획 또는 실패로 처리한다. 실제 주문·결제·전송과 마이크 음성 인식률은 이 검색 시험의 범위가 아니다.
