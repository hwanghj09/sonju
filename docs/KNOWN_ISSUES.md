# SonjuAI 남은 검증·출시 과제

이 문서는 `codex/autonomous-ui-agent-v2`의 현재 소스 기준 인수인계입니다. JVM 테스트와 빌드 성공은 Android 실기기, OEM 접근성 구현, 실제 Gemini 응답, Google Play 승인까지 보증하지 않습니다.

## 현재 구현 상태

- 기본 명령 파이프라인은 앱별 hard-coded navigator 대신 `AutonomySession`과 범용 도구를 사용합니다.
- 최종 목표는 세션에서 고정되고 앱·페이지·도구·전략·완료 조건만 재계획됩니다.
- 접근성 노드 클릭, 정규화 좌표 클릭, 텍스트 입력, 상·하·좌·우 스크롤, 앱/시스템 화면 열기, global action이 구현되어 있습니다.
- 같은 화면의 같은 action이 두 번 실패하면 다음 계획에서 다른 경로를 요구합니다.
- 성공 trace의 loop를 제거하고 비용이 더 낮은 경로만 최대 80개·90일 저장합니다.
- 일반 조작은 자동 실행하고 결제 확정·개인정보 입력·민감 화면 좌표 클릭만 사용자 확인을 거칩니다.
- 민감 accessibility node가 있는 화면은 원본 screenshot을 원격 모델에 보내지 않습니다.
- 과거 배민 전용 코드는 호환을 위해 남아 있지만 일반 명령 parser에서는 분리됐고 `startBaeminOrder`는 deprecated입니다.

### 2026-08-24 로컬 검증 스냅샷

- 임시 ASCII `R:` 경로에서 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 성공
- JVM 테스트 130개, failure 0, error 0, skipped 0
- Android lint error 0, warning 75. 경고는 주로 기존 unused resource, 아이콘/레이아웃 스타일 항목이며 출시 전 별도 정리가 필요합니다.
- debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 149,602,082 bytes, SHA-256 `84722A3834F7380A6CF8D743A73E6653759CFFC08E56A0A068A9B4B37598CA5C`
- 실제 Gemini API 요청, emulator/instrumentation, 실기기 gesture·IME는 이 스냅샷에서 실행하지 않았습니다.

## P0 — 실기기에서 반드시 검증할 항목

### 1. 실제 Gemini 계획 계약

민감 정보가 없는 테스트 앱에서 로컬 규칙에 없는 명령을 실행해 다음을 확인합니다.

- 응답이 `final_goal`, `target_app`, `target_surface`, `required_tools`, `strategy`, `success_criteria`, `revision_reason`과 action 좌표 필드를 모두 반환하는지
- Interactions API가 현재 JSON Schema의 nullable number와 최대 2개 action을 받아들이는지
- 앱 화면 변화 후 final goal은 그대로이고 수정 가능한 필드와 revision reason만 바뀌는지
- 네트워크 timeout, HTTP 4xx/5xx, 잘못된 JSON에서 사용자 중단과 재시도가 정상 동작하는지
- logcat, crash report, 파일에 명령·화면 본문·API 키가 출력되지 않는지

### 2. 여러 앱을 가로지르는 범용 흐름

최소 세 종류의 실제 앱에서 다음 흐름을 각각 검증합니다.

- 앱 열기 → 탭/메뉴 이동 → 검색 필드 입력 → 결과 선택 → 완료 화면 판정
- 목록의 상·하 스크롤과 가로 pager의 좌·우 스크롤
- 같은 라벨 후보가 둘인 화면에서 임의 첫 후보를 누르지 않고 selector 또는 좌표 경로를 다시 계획하는지
- 앱 업데이트로 view ID나 트리 path가 바뀌어도 text/hint/content description으로 복구하는지
- 24개 도구·3분 상한과 음성 패널의 `중단`이 실제로 실행을 끝내는지

### 3. 좌표 도구와 screenshot privacy

Android 11 이상 비민감 Canvas/WebView 테스트 화면에서 다음을 검증합니다.

- 손주 overlay가 screenshot 전에 숨겨지고 복원되는지
- 시각 분석 결과가 직접 탭되지 않고 `CLICK_COORDINATE` 계획과 live revision 검사를 거치는지
- status/navigation bar, display cutout, 회전, multi-window에서 0~1 좌표 변환이 맞는지
- gesture가 취소되거나 같은 좌표가 두 번 실패하면 다시 누르지 않는지
- password/OTP/card node가 하나라도 있는 화면에서는 `takeScreenshot` 결과가 Gemini 요청으로 전달되지 않는지
- `FLAG_SECURE`, 캡처 실패, Android 10 이하에서 접근성 경로로 되돌아가거나 명확히 실패하는지

### 4. 텍스트 입력과 IME

- 빈 필드, 기존 값이 있는 필드, multiline, 검색창, Compose, WebView에서 `ACTION_SET_TEXT` 동작
- hint/view ID/path selector와 중복 editable field 거부
- 한글 조합, emoji, 줄바꿈, 최대 길이, 앱 자체 validation
- 입력 후 화면 fingerprint가 민감 redaction 때문에 바뀌지 않는 경우에도 다음 관찰이 계속되는지
- 전화번호·주소·이메일·계정·비밀번호·PIN·OTP 입력 전에 확인창이 실제 값과 대상을 보여 주는지

### 5. 결제·개인정보 최소 안전선

실제 돈이나 실제 개인정보를 사용하지 않는 sandbox/mock 앱에서만 검증합니다.

- 결제/송금/구매/주문 최종 확정 클릭은 확인 전 실행되지 않는지
- 일반 상품 탐색이나 결제 내역 보기처럼 최종 확정이 아닌 화면에서 불필요한 확인이 과도하지 않은지
- 민감 화면 좌표 클릭은 확인을 요구하고 접근성 기반 명시적 버튼 클릭은 정책 의도와 맞는지
- 취소 후 같은 action이 자동 실행되지 않는지

### 6. 최단 경로 기억

- `A → B → A → C` 성공 흐름이 `A → C`로 저장되는지
- 좌표 클릭보다 동일 결과의 접근성 node 클릭 경로가 우선되는지
- 더 긴 후속 성공이 기존 짧은 경로를 덮어쓰지 않는지
- 다른 앱·화면에 route hint를 잘못 직접 replay하지 않고 계획 참고로만 쓰는지
- SharedPreferences에 `SET_TEXT.value`, 원본 명령, 화면 text, screenshot이 없는지

## P1 — 품질·성능 검증

- Pixel, Samsung One UI, 태블릿에서 node action과 gesture 차이
- 시스템 글자/디스플레이 크기 최댓값, 다크 모드, 회전, split screen
- TalkBack 동시 사용, 다른 overlay, 키보드가 열린 상태의 좌표와 스크롤
- 2,000-node/24-depth truncated tree에서 모델 token 크기와 누락 대상 처리
- 긴 세션의 배터리, 메모리, snapshot executor backlog, Gemini 호출 수와 latency
- Vosk wake word foreground service와 자율 실행이 동시에 동작할 때 마이크·TTS·overlay 수명
- 앱 강제 종료, 서비스 재연결, 기기 재부팅 후 진행 중 세션과 route memory 일관성

## Windows 빌드 주의사항

현재 한글 checkout 경로에서는 AGP/Gradle test worker가 생성된 Kotlin test class를 runtime에서 찾지 못해 모든 테스트가 `ClassNotFoundException`으로 실패합니다. 클래스 파일은 정상 생성되므로 제품 코드 실패로 오인하지 마십시오. 임시 ASCII drive를 매핑하면 같은 checkout에서 테스트가 실행됩니다.

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

## 출시 차단 사항

### API 키와 데이터 처리

- debug APK의 `BuildConfig.GEMINI_API_KEY`는 추출 가능합니다. 현재 키를 외부 배포 전에 교체하십시오.
- release build는 키를 비워 두지만, 실제 출시 구조는 서버 프록시 또는 보호된 모바일 AI 게이트웨이와 abuse/rate limiting이 필요합니다.
- `store=false`는 전체 Zero Data Retention 보장이 아닙니다. 실제 제공자 계약과 개인정보 고지를 별도로 검토해야 합니다.

### AccessibilityService 정책

- 현재 서비스는 `isAccessibilityTool=false`이며 사용자가 수동 활성화해야 합니다.
- 범용 AI가 다른 앱을 계획·조작하는 구조는 현재 형태로 Google Play 배포 완료를 주장할 수 없습니다.
- 출시 전 핵심 장애 지원 목적, 사용자 고지, 데이터 사용, 자동화 범위, 사람 확인 지점을 정책 전문가와 검토하고 필요한 선언·심사를 준비해야 합니다.

### 보안·운영

- production network layer에는 인증된 backend, certificate/host 검증, quota, telemetry redaction, kill switch가 필요합니다.
- learned route와 user feedback memory의 삭제 UI, 보존 기간 고지, 기기 이전 정책이 없습니다.
- crash reporting을 추가할 경우 명령, 화면 context, selector, 입력값이 수집되지 않도록 별도 redaction이 필요합니다.

## 완료 기준

다음 단계 완료를 주장하려면 최소한 아래 증거가 필요합니다.

1. JVM 전체 회귀 테스트, lint, debug assemble 성공
2. 실제 Gemini 구조 계획 성공·오류 경로 캡처
3. 서로 다른 세 앱의 자율 탐색·입력·완료 화면 검증
4. 민감 screenshot 차단과 결제/개인정보 확인 instrumentation 검증
5. 최단 경로 저장·회상·더 짧은 경로 교체의 기기 내 저장소 검증
6. 정책·키·backend 출시 차단 사항을 해소한 별도 release architecture
