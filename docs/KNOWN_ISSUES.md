# Sonju 남은 검증·배포 과제

최종 갱신: 2026-07-18

이 문서는 현재 프로토타입을 다음 작업자가 과장 없이 이어받기 위한 인수인계 문서입니다. 아래 항목은 코드에 남겨 둔 `TODO`가 아니라, 실제 Android 기기·Gemini 운영 환경·Google Play 정책까지 포함한 남은 검증 및 출시 과제입니다.

## 현재 확정된 상태

- Android debug APK 컴파일·조립·에뮬레이터 재설치가 성공했습니다.
- ASCII 임시 경로의 깨끗한 복사본에서 `testDebugUnitTest`, `lintDebug`, `assembleDebug`가 모두 성공했습니다. 단위 테스트는 76개, 실패·오류·건너뜀은 0개였습니다.
- 최종 APK는 `apksigner`의 APK Signature Scheme v2 검증을 통과했고, package `com.hwanghj09.sonju`, minSdk 28, targetSdk 36, 앱 라벨 `손주`를 확인했습니다.
- 접근성 서비스의 사용자 고지와 수동 활성화, 플로팅 `손` 버튼, 큰 글씨 홈 화면, 로컬 설정 열기 계획까지 에뮬레이터에서 확인했습니다.
- 최종 비동기 화면 캡처 리팩터링 후 `compileDebugKotlin`과 `compileDebugUnitTestKotlin`은 성공했습니다.
- 화면 전체 의미 트리 순회는 단일 백그라운드 executor에서 처리합니다. 메인 스레드는 최종 node path와 상태를 짧게 재검증한 뒤 Android 접근성 동작만 전달합니다.
- 설정 경로 신뢰값은 캐시에서 그대로 재사용하지 않습니다. 현재 살아 있는 공식 Settings Intent 경로, Activity, 첫 화면 제목이 모두 일치할 때만 다시 붙이고, 만료·이탈 시 제거합니다.
- 실행 중 놓친 접근성 이벤트 때문에 이전 토글 상태가 재사용되지 않도록 실행 종료 뒤 캐시를 무효화하고 새 스냅샷을 비동기로 요청합니다.
- 비동기 검증 중 중단·서비스 interrupt가 발생해도 실행 completion을 정확히 한 번 전달하고, UI가 진행 상태에 남지 않도록 했습니다. 중단 시 진행 중 오버레이 캡처와 대기 문맥도 함께 폐기합니다.
- worker/Binder 지연 뒤에도 클릭·스크롤 직전 45초 실행 상한을 다시 검사합니다.
- 공식 Settings 경로가 확립된 뒤 손주와 Settings가 아닌 다른 앱/Home으로 이탈하면 신뢰 경로를 즉시 폐기합니다.
- 진단용 화면 트리·제목 로그는 소스에서 제거했습니다.

## P0 — 다음 작업자가 먼저 끝낼 검증

### 1. 최종 리팩터링 이후 설정 토글 E2E 재검증

리팩터링 전 AOSP 에뮬레이터에서 `Dark theme` 스위치를 손주가 실제로 켠 것까지 확인했습니다. 그러나 캐시 신뢰 경로와 메인 스레드 순회를 고친 **최종 코드**에서는 전체 시나리오를 다시 끝까지 실행하지 못했습니다. 다음 순서로 회귀 검증하십시오.

1. `app/build/outputs/apk/debug/app-debug.apk`를 설치합니다.
2. Android 설정에서 `손주 화면 도우미`를 사용자가 직접 켭니다. `adb shell settings put secure ...`로 켠 서비스는 실제 이벤트 전달 상태가 수동 활성화와 달랐으므로 합격 근거로 사용하지 않습니다.
3. 손주 홈의 `디스플레이 설정 열기`를 누릅니다.
4. `Display & touch` 첫 화면에서 플로팅 `손` 버튼을 누릅니다.
5. `다크 테마 끄기` 또는 `Turn off Dark theme`를 입력합니다.
6. 확인 시점과 실행 시점의 fingerprint가 같을 때만 정확한 Settings switch node가 한 번 눌리는지 확인합니다.
7. `adb shell cmd uimode night` 결과가 `Night mode: no`인지 확인합니다.
8. `adb logcat -d | Select-String 'ANR in com.hwanghj09.sonju|FATAL EXCEPTION'`에 앱 오류가 없는지 확인합니다.
9. 같은 명령을 다시 실행했을 때 이미 원하는 상태라는 안내만 하고 스위치를 재토글하지 않는지 확인합니다.

실패하면 우선 다음 코드를 확인하십시오.

- `SonjuAccessibilityService.acceptObservedSnapshot`: Activity와 첫 화면 제목이 확립된 뒤에만 `trustedSettingsRoute`를 부여하는지
- `captureLiveSnapshotAsync`: worker 캡처 중 `epoch`가 변하면 최종 동작이 fail-closed 되는지
- `clickValidatedNodeAsync`: 동일 root의 fingerprint, Settings switch ID, 현재 상태, 확정 node path를 모두 다시 검사하는지
- `scheduleObservedSnapshotRefresh`: 실행 종료 뒤 이전 캐시를 재사용하지 않는지

### 2. Gemini 실 API 경로 검증

로컬 규칙 명령은 Gemini를 호출하지 않으므로 설정 열기·다크 테마 테스트만으로 API 통합이 검증되지는 않습니다. 민감 정보가 없는 테스트 화면에서 로컬 규칙에 없는 안전한 탐색 질문을 사용해 다음을 확인해야 합니다.

- Interactions API `v1` 요청이 현재 `gemini-3.1-flash-lite`에서 성공하는지
- 구조화 JSON 응답이 `GeminiPlanner`의 schema/parser와 일치하는지
- `store=false`가 실제 요청 본문에 포함되는지
- HTTP 오류, 타임아웃, 취소 시 어떤 외부 동작도 수행하지 않는지
- 명령·화면 트리·모델 응답이 logcat이나 파일에 남지 않는지

API 키는 채팅에 한 번 노출되었고 debug APK에도 포함될 수 있습니다. 기존 키를 폐기·재발급한 뒤 테스트하고, 키 문자열을 이 문서·소스·이슈·로그에 붙이지 마십시오.

### 3. 최종 전체 회귀 명령

Windows의 상위 경로에 한글이 있어 Gradle 테스트 worker가 간헐적으로 실패할 수 있습니다. 최종 검증은 ASCII 임시 복사본 또는 `C:\src\sonju` 같은 ASCII 체크아웃에서 실행하십시오.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

추가 확인:

```powershell
git diff --check
rg -n "SonjuToggleProbe|Log\.d" app/src/main
git ls-files local.properties
```

첫 명령은 모두 성공해야 하고, 두 번째 묶음은 공백 오류·진단 로그·추적된 `local.properties`가 없음을 보여야 합니다.

2026-07-18 최종 실행에서는 위 Gradle 세 작업과 `git diff --check`가 성공했고, 진단 로그·로컬 키 노출 파일·추적된 `local.properties`가 없었습니다. 이 명령은 다음 코드 변경 후 다시 실행해야 합니다.

## Android 플랫폼상 남는 기능 한계

다음은 VLM을 추가한다고 자동으로 해결되는 버그가 아닙니다. 손주는 이런 화면에서 좌표를 추측하지 않고 중단해야 합니다.

- AOSP 에뮬레이터의 Wi-Fi 첫 화면은 UIAutomator에는 master toggle이 보이지만 제3자 `AccessibilityService` 트리에는 노출되지 않았습니다. 따라서 현재 안전 정책으로는 Wi-Fi 설정 화면을 열 수는 있어도 master toggle을 직접 바꿀 수 없습니다.
- `FLAG_SECURE` 금융·DRM·보안 창, 잠금·생체 인증·권한 승인 화면은 읽거나 조작하지 않습니다.
- 접근성 의미를 제공하지 않는 Canvas, 게임, 일부 WebView/SurfaceView의 픽셀 콘텐츠는 읽지 않습니다.
- 같은 라벨 후보가 여러 개거나, 트리가 2,000개 노드/깊이 24를 넘어 잘렸거나, 확인 뒤 화면이 바뀌면 중단합니다.
- OEM마다 Settings Activity 이름·제목·switch resource ID가 다를 수 있습니다. 허용목록에 명시적으로 검토해 추가하기 전에는 토글을 차단하는 것이 정상입니다.
- 외부 앱의 임의 클릭과 글자 입력은 현재 의도적으로 차단되어 있습니다. “모바일 폰의 모든 요소”를 포괄적으로 조작하는 제품 요구와 안전·플랫폼·Play 정책은 양립하지 않으므로 제품 범위를 다시 합의해야 합니다.

## P1 — 실기기 품질 검증

최소 다음 매트릭스가 필요합니다.

- Pixel/AOSP API 28, 34, 36 및 삼성 One UI 최신 2개 버전
- 시스템 글자 크기·디스플레이 크기 최댓값, 화면 회전, 다크/라이트 테마
- TalkBack 동시 사용, 스위치 제어 등 다른 접근성 서비스와의 충돌
- 한국어/영어 IME, 음성 인식 서비스 미설치·오프라인·권한 거부
- 서비스 강제 종료·업데이트·재부팅·오버레이 길게 누르기 중단
- 느린 기기에서 연속 접근성 이벤트가 발생할 때 ANR·배터리·메모리·executor backlog
- 네트워크 단절, Gemini 4xx/5xx, 느린 응답, 앱 백그라운드 전환 중 취소
- secure window, WebView, 중복 라벨, 긴 목록, 동적으로 사라지는 토글에서 fail-closed

접근성 대상 사용자와 함께 과업 성공률, 오작동률, 도움 요청 완료 시간, 확인 문구 이해도를 별도 사용성 테스트해야 합니다.

## 출시 차단 사항

### API 키와 백엔드

- debug APK의 `BuildConfig`에 들어간 키는 APK에서 추출될 수 있습니다.
- 현재 키를 즉시 교체하고, 공개 배포판은 모바일 클라이언트 직접 Gemini 호출을 제거해야 합니다.
- 출시 구조는 서버 프록시 또는 Firebase AI Logic + App Check, 사용자별 rate limit, 비용 한도, abuse monitoring을 갖춰야 합니다.
- `store=false`는 Interactions 상태 저장을 끄는 옵션이지 전체 Zero Data Retention 보장이 아닙니다. 실제 조직의 Gemini 데이터 처리 조건을 법무·개인정보 담당자가 검토해야 합니다.

### Google Play 접근성 정책

- 현재 manifest는 `isAccessibilityTool=false`이며, AI 계획이 접근성 API 실행으로 이어지는 형태는 그대로 Play 제출하면 안 됩니다.
- 제출판은 AI를 안내 전용으로 제한하고 사람이 정의한 결정적 규칙만 실행하도록 좁히거나, 장애 지원이 앱의 명확한 핵심 목적임을 입증하고 AccessibilityService 선언·별도 심사를 준비해야 합니다.
- 개인정보 처리방침, 인앱 명확한 고지·동의, 데이터 안전 섹션, 접근성 API 사용 영상과 심사 설명이 필요합니다.

## 현재 작업공간 주의사항

- `.idea/`는 기존 사용자 파일로 보고 수정·삭제하지 않았습니다.
- `local.properties`는 Git에서 제외되어야 하며 절대 커밋하지 마십시오.
- 아직 커밋이나 브랜치 생성은 하지 않았습니다. 다음 작업자는 `git status --short`와 `git diff --check`부터 확인하십시오.
- 안전 규칙을 완화할 때는 `SafetyPolicy`, `RuleBasedPlanner`, 관련 단위 테스트, 인앱 개인정보 고지, `docs/SAFETY_CONTRACT.md`를 함께 갱신해야 합니다.

## 완료 기준

프로토타입 단계의 다음 완료 기준은 다음과 같습니다.

1. 위 P0 설정 토글 E2E가 최종 코드에서 통과한다.
2. 안전한 Gemini 실 API 요청 1건과 실패 경로가 검증된다.
3. 단위 테스트·Lint·assemble·APK 서명 검증이 모두 통과한다.
4. logcat에 ANR/크래시/민감 진단 로그가 없다.
5. 새 키가 APK에 직접 포함되지 않는 출시 아키텍처가 결정된다.
6. Google Play 접근성 정책에 맞춘 제품 범위와 제출 전략이 결정된다.
