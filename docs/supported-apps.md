# 지원 범위

현재 MVP는 특정 브랜드의 private API나 고정 좌표에 의존하지 않는 범용 Android 접근성 흐름을 지원한다.

실행 시 Android launcher catalog에서 설치 앱 이름과 package를 읽어 세션 동안 캐시한다. 따라서 별칭 목록에 없는 앱도 `앱 이름 + 내부 메뉴/검색 요청`으로 분리할 수 있다. 앱 안에서는 유일한 semantic 메뉴 target 또는 `검색 control → 입력란 → 검색 제안/결과`를 한 번에 한 동작씩 실행하고 매번 화면을 다시 관찰한다. 같은 task와 화면에서 검증된 skill이 있으면 이 범용 탐색보다 먼저 재사용한다.

## 지원 가능한 화면

- 표준 `AccessibilityNodeInfo`에 text, content description, view id, role 또는 action이 노출되는 앱
- 유일한 semantic selector로 찾을 수 있는 button, input, list, scroll container
- node action이 실패해도 같은 검증 node의 bounds 안에서 gesture가 가능한 화면
- 민감 node가 없고 접근성 grounding이 실패했을 때만 제한적으로 분석하는 안전한 screenshot 화면

## 지원하지 않거나 보장하지 않는 화면

- 잠금 화면, 생체 인증, `FLAG_SECURE`, DRM 및 Android가 차단한 보안 경계
- 비밀번호, OTP, 결제수단 등 민감정보 자동 입력
- 결제·송금·구매 final commit의 자동 확정
- 접근성 tree와 screenshot 양쪽에 나타나지 않는 SurfaceView·게임 화면
- 2,000 node 또는 depth 24 제한으로 잘린 tree에서의 고위험 동작
- OEM이 accessibility action/gesture를 거부하는 화면

앱별 `AppAdapter`, state extractor, interruption handler 인터페이스는 마련되어 있지만 현재 저장소에는 production 호환성을 보증하는 앱별 adapter가 등록되어 있지 않다.

카카오톡 프로필과 삼성 노트 최근 항목처럼 실기기에서 구조가 확인된 경로는 성공률을 높이는 선택적 fast path다. 이 fast path가 범용 앱 라우터를 대체하지는 않는다. 동일 label의 후보가 여러 개이거나 화면 tree가 잘렸거나 검색 정렬 조건을 확인할 수 없으면 하나를 추측하지 않고 skill/model/VLM 순서의 제한된 fallback으로 넘긴다.

배민 주문 문장은 앱(`배민`)과 검색어를 구조화해 잘못된 앱 이름으로 해석하지 않는다. 앱 실행, 검색 화면 진입, 검색어 입력, 유일한 검색 제안 선택까지는 로컬의 단계별 검증 경로를 사용하고, 이후 식당·메뉴·옵션 화면은 범용 관찰→계획→검증→실행 루프로 넘긴다. 여러 후보 중 하나를 임의로 선택하지 않으며 결제 final commit은 차단한다. 과거의 배민 legacy executor는 계속 비활성화되어 있고, OEM/배민 버전별 전체 주문 흐름은 실기기 E2E 전까지 production 보장 범위가 아니다.
