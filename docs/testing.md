# 에이전트 검증 가이드

## 로컬 검증

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --console=plain
```

상위 경로의 한글 때문에 Gradle test worker가 `ClassNotFoundException`을 내면 저장소를 이동하지 말고 임시 ASCII drive를 사용한다.

```powershell
$repo='C:\Users\Taeyoon\Desktop\태윤\Codes\sonju'
subst R: $repo
try {
    Push-Location R:\
    $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
    .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug '-Pkotlin.incremental=false' --no-configuration-cache --no-daemon --console=plain
} finally {
    Pop-Location
    subst R: /d
}
```

`AgentArchitectureTest`는 semantic parsing/fingerprint/pruning, clickable ancestor와 ambiguity, 좌표 출처, revision/node binding, risk confirmation, sensitive/truncated fail-closed, predicate/goal, parameterized offline fast path, loop detection, log redaction을 검증한다.

배민 회귀 검증은 다음 계약을 포함한다.

- `배민에서 피자 시켜줘`를 `배민에서 피자 시` 앱 실행으로 잘못 자르지 않는다.
- canonical task가 `appId=baemin`, `taskType=order_food`, `query=피자`, `risk=HIGH`를 보존한다.
- 앱 실행과 검색 prefix는 재관찰을 요구하고, 복수 식당·메뉴 후보는 자동 선택하지 않는다.
- 검색 결과의 `피자` 또는 과거 주문 완료 문구만으로 새 주문 성공을 선언하지 않는다.
- 결제 control은 원래 문장에 `결제`가 없어도 critical action으로 차단한다.

## 실기기 체크

1. 접근성 서비스가 꺼진 상태와 권한 철회 후 graceful failure를 확인한다.
2. 표준 앱에서 search button, input, result 확인 시나리오를 실행한다.
3. 목록 scroll 후 동적 항목 클릭을 확인한다.
4. 같은 성공 task를 다시 실행해 model 호출 없는 skill fast path를 확인한다.
5. icon-only 안전 화면에서만 VLM 폴백과 verifier 통과를 확인한다.
6. payment/OTP/ambiguous/truncated/화면 변경 시 executor가 fail closed 하는지 확인한다.
7. 중단 버튼, notification action, session timeout에서 즉시 취소되는지 확인한다.

단위 테스트와 APK 생성은 실기기, Gemini/VLM 운영 API, OEM별 동작, 배터리·메모리, Play 정책 승인을 증명하지 않는다. 결과 보고에서 각 경계를 분리한다.
