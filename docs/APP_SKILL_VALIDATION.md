# Sonju 범용 학습·복구 구현과 검증

검증 기간: 2026-09-12~13. 기기: Samsung SM-F731N / Android API 37.

이 문서는 당시 기록이다. 후속 구현과 2026-09-21 실기기 전체 경로 재사용·복구 결과는 [최신 검증 기록](APP_SKILL_LIFECYCLE_20260921.md)을 참고한다.

## 구현된 실행 흐름

새 작업은 현재 화면과 설치 앱 목록을 AI에 전달한다. 검증된 AppSkill이 맞으면 현재 요청의 입력값으로 재사용한다. 처음 보는 의역은 AI가 제시된 저장 경로와 인자를 선택할 수 있고, 완료 후 해당 표현을 재사용에 반영한다.

실행 전 실패는 새 관찰에서 한 번 재시도한다. 반복 실패, 변경된 화면, 실행 효과가 불확실한 경우는 최초 실패 지점부터 AI가 이어간다. 반복 실패한 클릭 대상은 구조화 응답의 허용 목록에서도 제외한다. 검증된 목표에 도달한 뒤 관찰된 경로를 최적화하고 기존 스킬을 갱신한다. 앱 진입을 건너뛴 실행에서도 실제 관찰한 앞부분과 복구 구간을 연결한다.

입력·제출·상태 변경·인증 경계는 경로 단축으로 없애지 않는다. 부수 내용이 바뀌어도 직전 단계와 현재 앱·대상·역할·라벨·상태가 검증되면 다음 단계를 이어간다. 원문 요청 및 입력값 대신 해시, 템플릿과 요청 구간을 AppSkill에 보관한다. 겹치는 인자의 요청 구간은 원문 해시가 완전히 같을 때만 적용한다.

모든 실행은 현재 관찰에 묶인 `VerifiedPlan`과 실행 직전 재검증을 거친다. 완료 선언만으로 성공 처리하지 않는다. 이전 입력값의 정적 결과를 새 입력값의 성공 근거로 쓰지 않는다. 상세 계약은 [skill-format.md](skill-format.md)에 있다.

## 자동 검증

- JVM/실 API 테스트: **317개, 실패 0, 오류 0, 생략 0**. 실제 모델 검사 6개를 포함한다.
- Android 요청 패턴 검사: **1개 통과**. JVM과 Android ICU의 정규식 차이로 발생한 초기화 종료를 재현하고 수정했다.
- `lintDebug`: **오류 0, 경고 81**.
- `assembleDebug`: 성공.
- 최신 APK와 기기에 설치된 `base.apk`의 SHA-256 일치: `d2924eb4b9e805edd33508f111e4a5d99fae52e510a320db9ffa5e13992cff3a`.

다양한 입력값과 의역, 저장/복원, 겹치는 인자, 앱 진입 생략 후 복구, 실패 중 중복 저장 방지, 잘못된 결과·대상·앱 거부, 입력/확정 단계 보존, 중복 라벨의 검증된 경로, 반복 실패 클릭 제외, 기기 잠금 대기·원래 목표 복귀를 검사했다. 실제 모델 검사에는 구조화 계획, 정보 조회, 이미지 폴백, 의역으로 스킬 선택, 클릭이 제외됐을 때 다른 도구 선택이 포함된다.

실제 빌드는 체크아웃을 임시 ASCII 드라이브 `R:`로 매핑하고 Android Studio JBR을 사용했다.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:SONJU_LIVE_MODEL='1'
& R:\gradlew.bat -p R:\ :app:testDebugUnitTest :app:lintDebug :app:assembleDebug '-Pandroid.overridePathCheck=true' '-Pkotlin.incremental=false' --console=plain --max-workers=1
```

## 개발 중 직접 확인한 기기 결과

아래 수치는 해당 실행에서 관측한 결과다. 서로 다른 시작 화면을 동일 조건의 성능 비교로 취급하지 않는다. 음성 인식 후의 텍스트 전달 경로를 실행했으며 마이크 인식률을 측정한 결과가 아니다.

| 실행 | 도구 | AI 호출 | 시간 | 확인 |
|---|---:|---:|---:|---|
| 배터리 정보 메뉴 첫 탐색 | 7 | 8 | 42.3초 | 실패 후 재계획을 거쳐 목표 화면 도달 |
| 이미 열린 같은 결과 재요청 | 0 | 0 | 0.4초 | 실제 결과를 AppSkill로 확인 |
| 홈에서 앱 복귀 후 같은 결과 확인 | 1 | 0 | 3.2초 | 저장된 앱 열기와 완료 확인 |
| 실패 경로 복구 및 저장 | 5 | 6 | 38.2초 | 같은 스킬 ID의 v4·ACTIVE 저장 확인 |
| Chrome에서 example.com 열고 제목 조회 | 3 | 4 | 25.4초 | Chrome의 Example Domain 본문/제목 확인 |
| Android 잠금 중 요청 | 0 | 0 | — | `DEVICE_UNLOCK` 사용자 대기 확인 |

증거는 `app/build/reports/completion-qa/`의 각 `result.json`, `flow.log`, `screen.xml`과 `app/build/reports/skill-lifecycle/`에 있다. 주요 실행 이름은 `skill-settings-discovery-ready`, `skill-settings-reuse`, `skill-settings-final-replay`, `skill-settings-repair-complete`, `skill-web-first`다. 잠금 검증은 `locked-device-result.json`과 `locked-device.log`, 스킬 저장 확인은 `final-skill-metadata.json`에 있다.

## 남은 확인과 지원 경계

휴대폰이 자동 잠겨, 마지막 입력 구간 복원/기존 스킬 보완 이후의 **처음부터 끝까지 진행하는 실기기 경로 재검증은 대기 중**이다. 앞서 목표에는 도달했지만 AI 호출 0 기준을 충족하지 못한 전체 경로 검사도 있었다. 이를 해결하는 복구·인자·대상 검증은 자동 테스트를 통과했으나, 위의 0회 결과를 전체 경로의 0회 보장으로 확대하지 않는다. 잠금 검증 후 테스트 요청은 중단해 두었다.

잠금을 해제한 뒤 `scripts/verify-device-command.py`의 `--no-model --min-tools 3`으로 실제 경로 실행과 AI 호출 수를 함께 확인할 수 있다. 화면에 목표가 이미 있는 경우와 실제 조작을 분리해야 한다.

모든 앱·웹·명령의 100% 성공률, 마이크 음성 인식, 인증·금융·결제 E2E, 모든 제조사/Android 버전은 검증하지 않았다. Android가 노출하지 않는 화면, 보안 창, 사용자 인증 및 현재 도구가 지원하지 않는 조작에는 제한이 있다. 최단 경로는 실제 관찰한 검증 가능한 전이 안에서 선택한다. 새로운 표현/동적 결과/불명확한 인자에는 AI 호출이 필요할 수 있다.

현재 산출물은 기존 프로젝트 설정을 사용하는 개발 APK다. 공개 배포용 서버 인증·AI 게이트웨이 및 스토어 심사까지 완료한 출시 버전이라는 뜻은 아니다.
