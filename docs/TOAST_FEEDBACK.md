# 토스트 관찰·판단 검증

2026-09-21, Samsung SM-F731N / Android API 37에서 검증했다.

## 변경

기존 서비스 설정에는 `TYPE_NOTIFICATION_STATE_CHANGED` 구독이 없었고, 일반 화면 트리 수집은 실행 중에 생긴 짧은 토스트 본문을 보존하지 않았다. Android 토스트는 이 이벤트의 `text`로 전달되고 source node가 없으므로 이벤트에서 직접 읽는다. 근거: [Android AccessibilityEvent 문서](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent), [AOSP ToastPresenter](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/main/android-35/android/widget/ToastPresenter.java).

- 현재 전면 앱이 보낸 `android.widget.Toast`만 수집한다. 손주 자신의 토스트, 일반 Notification, 다른 앱과 오래된 이벤트는 제외한다.
- 원문을 기존 개인정보 마스킹에 통과시킨 뒤 메모리에 최대 4개 보관하며, 최근 30초 내용만 현재 앱의 관찰에 제공한다. 잠금·인증·민감 화면에서는 모델 입력에서 제외한다.
- `recent_toasts_read_only`로 AI에 전달한다. 클릭 가능한 node, 화면 지문, 저장된 완료 근거로 만들지 않는다. 원문 토스트는 AppSkill과 일반 실행 로그에 저장하지 않는다.
- 새 토스트는 진행 중인 계획을 다시 확인하게 한다. AppSkill 실행 중에는 현재 단계부터 AI가 안내를 해석한다. 모델 응답을 기다리거나 완료 화면을 재관찰하는 사이에 발생한 안내도 반영한다.
- 토스트 응답이 있는데 실제 내용 변화가 없으면 클릭·검색 제출을 자동으로 재시도하지 않는다. 포커스·배치 변화만으로 성공 처리하지 않고 현재 결과를 다시 판단한다. 효과가 미검증인 동작을 경로 최적화에서 지워 성공 경로처럼 학습하지 않는다.

## 검증

- JVM: 353개 중 **343개 통과**, 선택 실행 10개 제외, 실패·오류 0. 보관 한도·만료·앱 구분·개인정보 마스킹·읽기 전용 전달·AppSkill AI 전환·미검증 경로 학습 방지를 포함한다.
- 실제 Android 토스트 검사 **1개 통과**. 실행 중 이벤트를 수신하고, 토스트가 사라진 뒤 3.5초가 지나도 계획 입력에서 내용을 확인했다. 기본 버튼이 두 번 눌리지 않았음을 실제 UI의 실행 횟수 1로 확인했다.
- 실제 모델·기기 명령: `기본 항목을 실행해보고 사용할 수 없다는 안내가 나오면 대체 항목을 실행해줘` → 기본 항목 1회 → `기본 항목은 사용할 수 없습니다. 대체 항목을 선택하세요.` 토스트 → 대체 항목 실행 → `대체 작업 완료` 확인. **16.4초, 도구 시도 2회, AI 호출 3회**. `--exact-text`로 완료 문구와 기본 항목 실행 횟수 1을 검증했다.
- 앱/AndroidTest APK 빌드 성공. 최종 단위 검사와 린트 재실행 성공. 린트 오류 0, 기존 경고 81. 최신 개발 APK를 삼성 기기에 설치하고 로컬/설치 APK SHA-256 일치를 확인했다.

증거: `app/build/reports/toast-qa-20260921/`의 `unit/`, `device-test.log`, `device-flow.log`, `apk-verification.json`; 실제 AI 실행은 `app/build/reports/completion-qa/toast-aware-recovery-20260921/`의 `flow.log`, `screen.xml`, `result.json`에 있다. 최초 기기 검사에서 발견한 화면 변화 오판 자료는 `device-before-postcondition-fix.log`로 구분했다.

검증 대상은 Android가 접근성 이벤트로 전달하는 실제 Toast다. 이벤트를 제공하지 않는 자체 Canvas 알림이나 다른 제조사의 모든 구현까지 검증한 것은 아니다. 일반 화면 안의 배너·Snackbar는 기존 화면 트리 관찰 경로를 따른다.
