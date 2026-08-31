# AppSkill 저장 형식

`AppSkill`은 화면 좌표를 재생하는 macro가 아니라 canonical task와 semantic screen 전이에 묶인 parameterized workflow다.

```text
AppSkill
  skillId, appId, taskType, name, description
  parameters[]: name, type, required, description
  entryFingerprint, exitFingerprint
  steps[]
  risk, version, confidence
  successCount, failureCount, lastValidatedAt, status

SkillStep
  stepId, entryFingerprint
  action: type, targetTemplate, valueTemplate
  expectedAfterFingerprint, retryPolicy, fallbackPolicy
```

## 저장 규칙

- 저장 가능한 행동은 click, set-text, scroll, open-app, back, home뿐이다.
- `CLICK_COORDINATE`, screenshot, 원문 요청, 화면 본문, 비밀번호·OTP·결제정보는 저장하지 않는다.
- 입력값은 반드시 `${parameter}` placeholder로 일반화한다. 일반화할 수 없는 `SET_TEXT` trace는 skill 후보에서 제외한다.
- fast path는 task type, app id, 현재 fingerprint가 맞고 상태가 `ACTIVE`인 skill만 사용한다.
- 성공 시 confidence를 최대 1.0까지 `+0.05`, 실패 시 최소 0까지 `-0.15` 갱신한다. 첫 실패는 `SUSPECT`, 누적 3회 실패는 `NEEDS_REPAIR`다.
- 로컬 저장은 최대 100개 skill로 제한하고 `SkillRepository` 인터페이스 뒤에 둔다.

현재 on-device 형식은 `LocalSkillRepository`가 관리하는 versioned JSON이다. 클래스 필드가 canonical schema이며 외부에서 JSON blob을 직접 수정하지 않는다.
