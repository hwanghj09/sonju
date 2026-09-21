# 브라우저 입력과 웹 본문 관찰 수정

2026-09-13 검색 제출·자동완성 완료 판정·웹 입력창 재연결의 후속 수정과 최신 검증은 [검색 제출 검증 기록](SEARCH_SUBMISSION_RELIABILITY.md)에 정리했다. 아래 내용은 당시 검사 기록이다.

2026-09-12, Samsung SM-F731N에서 재현한 문제와 공통 코드 수정이다. `여기`, 역 이름, 기차표 명령, 특정 사이트를 특별 취급하는 실행 분기는 추가하지 않았다.

## 확인한 원인

1. **입력 완료**: 제출 검증기가 `IME_ENTER`를 노출한 검색 입력란과 요청 문장에 그대로 들어 있는 짧은 검색어만 허용했다. 주소창의 리소스 ID, 생성한 검색어, URL은 이 조건을 통과하지 못했다. 실제 삼성 브라우저 주소창에는 `SET_TEXT`만 있고 `IME_ENTER`가 없었다. 실행기도 접근성 `ACTION_IME_ENTER`에 의존했다. 이 동작의 `EditorInfo.actionId`와 실제 키보드의 `imeOptions`에 지정된 검색·이동 동작은 다르다.
2. **입력란 재연결**: 키보드를 열면 같은 주소 입력란의 경로와 위치가 달라졌다. 같은 패키지·창·리소스 ID의 입력란인데도 이전 경로나 좌표를 요구해 입력 후 검증 또는 제출 직전 검증이 실패했다. 검색 후 주소창에 검색어 대신 URL이 나타나는 것도 로컬 계획기가 다시 입력해야 할 상태로 오인했다.
3. **앱 이름 오인**: `에서` 앞의 임의 명사구를 앱 이름으로 확정했고, 서비스가 설치 목록을 조회하기 전에 이 결과로 앱 실행 경로를 만들었다. 원래 문장은 `OPEN_APP 여기` → `APP_NOT_INSTALLED`로 끝났다.
4. **본문 누락**: 코레일 페이지가 실제 화면에는 표시됐지만 Sonju의 관찰과 독립적인 UiAutomator 관찰 모두 브라우저 도구 모음만 반환했다. Google 결과와 Example Domain에서도 같은 현상을 확인했다. 후속 분석에서 설치된 Samsung Internet 30.0.2.61의 가상 웹 접근성 공급자가 서비스 허용 목록을 검사하고 조건에 맞지 않으면 `null`을 반환하는 구현을 확인했다. 아래 ‘후속 원인 분석’에 진단과 수정 범위를 기록한다.
5. **복구와 완료 판정**: Sonju가 도구 모음의 라벨 수를 충분한 화면 정보로 간주했고, 빈 `toolbar_progress_container`를 로딩으로 오인했다. 이미지 관찰 한도를 앞 화면에서 사용하면 실제 검색 성공 후에도 새 본문을 읽을 수 없었다. 저장된 완료 근거 역시 도구 모음만으로 통과할 수 있었다. ‘현재 웹페이지를 읽어줘’는 현재 화면 설명으로 분류되지 않아 불필요한 탐색으로 이어졌다.
6. **캡처에 섞인 Sonju 애니메이션**: 전체 디스플레이 캡처에 Sonju의 움직이는 테두리가 포함됐다. 본문이 같아도 이미지 해시가 달라져 시각 계획을 거부했다. Android 14 이상의 앱 창 캡처로 접근성 오버레이를 제외했다. 이전 API에서는 캡처 중 테두리 그리기만 억제하고 터치 차단은 유지한다.
7. **읽기 요청의 오래된 관찰**: 앱 복귀 중 얻은 34개 노드가 캡처 직전에는 33개로 바뀌어, 같은 페이지의 이미지 읽기가 사전 검증에서 중단됐다. 읽기 요청은 같은 앱·창을 새로 관찰한 뒤 그 관찰에 이미지 전송 검증을 적용한다. 다른 창이나 민감 화면으로 바뀌면 전송하지 않는다.
8. **접근성 스크롤 대상 누락**: 이미지에서 본문을 읽고도 네이티브 스크롤 노드가 없다는 이유로 스크롤 계획이 거부됐다. 현재 이미지가 다시 검증되고, 민감 정보가 없으며, 큰 렌더링 영역이 하나일 때만 해당 영역 안에서 기존 스와이프를 사용한다. 실행 후 이미지 변화도 확인한다.
9. **숨긴 안내 패널의 입력 차단**: 제스처 동안 안내 패널을 `INVISIBLE`로 바꿨지만 그 창의 터치 속성은 유지됐다. 삼성 브라우저에서 스와이프 완료 콜백이 와도 본문 픽셀은 그대로였다. 같은 화면에서 패널·음성 버튼 창에 일시적으로 `FLAG_NOT_TOUCHABLE`을 적용하자 실제 본문 이동과 이미지 변화 검증이 통과했다. 기존 화면 차단막과 시작점 주변 차단은 유지하며, 제스처 성공·실패·사용자 중단 모두에서 기존 창 속성을 복원한다.

Android의 [ACTION_IME_ENTER 계약](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction#ACTION_IME_ENTER)과 [접근성 입력기 기능](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INPUT_METHOD_EDITOR)을 확인했다. Chromium은 웹 본문을 별도의 가상 접근성 트리로 제공한다([구현 설명](https://chromium.googlesource.com/chromium/src/+/HEAD/docs/accessibility/browser/android.md)). 브라우저 도구 모음을 읽을 수 있다는 사실만으로 이 트리가 제공됐다고 판단할 수 없다.

Android의 [`takeScreenshotOfWindow`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))는 접근성 오버레이를 제외한 대상 창을 캡처한다. 창의 실제 위치에 픽셀을 배치해 기존 화면 좌표계를 유지하며, 창 크기가 맞지 않으면 사용하지 않는다.

## 변경한 공통 동작

- 설치된 실행 가능 앱 목록으로 앱 후보를 검증한다. 일치하지 않는 명사구는 원문에 남긴다. 출발지와 목적지는 별도로 보존한다. 기존 앱 별칭 해석도 설치 목록의 실제 앱과 연결한다.
- 계획기와 검증기가 같은 검색·주소 입력란 판별을 사용한다. 현재 관찰한 전체 입력값을 제출하며, 입력값을 임의로 바꾸거나 메시지·인증 입력란을 검색창처럼 제출하는 동작은 거부한다.
- Android 13 이상에서 접근성 입력기의 현재 편집기 정보를 사용한다. 실제 포커스·패키지·전체 입력값이 일치할 때만 `SEARCH`, `GO`, `DONE`을 호출한다. 기존 접근성 Enter 경로도 유지한다. 입력·제출 후 조건은 별도로 확인한다.
- 입력란의 창·패키지·클래스·메타데이터와 고유 ID를 검증해 경로 이동에 대응한다. 다른 창, 중복 ID, 민감 입력란은 재연결하지 않는다. 입력 전에는 기존 값이 유지됐는지도 검사한다.
- 한 번 성공적으로 제출한 검색어는 로컬 규칙으로 다시 입력하지 않고 결과 관찰로 넘긴다.
- 큰 `SurfaceView`·`TextureView`·`WebView` 내부의 내용이 접근성 관찰에 없는 상태를 식별한다. 두 번의 실제 관찰에서도 빠져 있으면 기존 이미지 관찰을 사용한다. 특정 패키지나 도메인을 조건으로 삼지 않는다.
- 실제로 검증된 네이티브 또는 이미지 기반 화면 전환이 다음 이미지 관찰의 근거가 된다. 중복 프레임 제한과 요청당 최대 6회 상한은 유지한다. 민감 화면 전송 제한도 유지한다.
- 본문 관찰이 빠져 있으면 앱 실행 자체를 제외한 완료 판정에는 검증된 이미지 근거가 필요하다. 도구 모음이나 저장된 경로만으로 페이지 작업의 완료를 선언하지 않는다.
- 현재 화면·페이지·문서의 내용을 읽는 요청은 읽기 경로로 처리한다.

## 검증 기록

단위 시험은 `BrowserReliabilityTest`와 기존 계획·검증 시험에 포함했다. 기본 시험, 실제 모델 시험, 기기 명령의 로그 및 화면은 `app/build/reports/browser-reliability-qa/`와 `app/build/reports/completion-qa/`에 보관한다. 초기 실패 기록도 남겨 두었다.

| 검사 | 결과 및 근거 |
| --- | --- |
| 기본 단위 검사 | 298개 통과, 선택 실행 모델 검사 4개 제외. `unit-final/` XML에 보관 |
| 실제 모델 검사 | 합성 화면으로 별도 실행한 4개 모두 통과. `live-model-final.xml` |
| 빌드·린트 | 앱 및 기기 시험 APK 빌드 성공. 린트 오류 0, 경고 81 |
| Android 에뮬레이터 | 음성 중단·확인·터치 차단·앱 목록·로그인 경계 등 13개 통과, 선택 모델 검사 1개 제외. `native-release.txt` |
| 삼성 브라우저 검색 | `브라우저에서 서울역 열차 시간표 검색해줘`: 실제 입력 → 키보드 GO → 결과 이미지 확인까지 12.5초. 동작 2회, 모델 1회. `completion-qa/browser-search-final-swipe/` |
| 삼성 브라우저 URL 제출 | `https://example.org/`를 입력하고 키보드 이동으로 제출: 23.4초, 동작 2회, 모델 3회. `completion-qa/browser-url-keyboard/` |
| 웹 본문 읽기 | Google 결과 제목·본문, Example Domain 제목·본문 확인. Example Domain 9.5초, 조작 0회. `page-read-final.json`, `page-read-final.png`, `page-read-google.png` |
| 최종 APK 웹 스크롤 | `현재 웹페이지를 위로 한 번 스크롤해줘`: 실제 이동·이동 후 이미지 검증·목표 완료까지 21.3초. 동작 1회, 모델 2회. `completion-qa/browser-scroll-release/` |
| 원래 문장 | `여기에서 서울역까지 가는 기차표 예매해줘`가 가짜 앱 `여기` 실행으로 종료되지 않고 설치된 지도 앱에서 로그인 필요 화면까지 진행. 45.8초에서 검사를 중단했으며 예매 완료 시험은 아님. `completion-qa/train-request-browser-final/` |

추가 웹 스크롤 검사에서 처음에는 전달 완료 콜백만 오고 실제 페이지는 움직이지 않았다(`scroll-probe.log`: `frame=true changed=false`). 안내 패널의 터치 창 속성을 수정한 뒤 같은 삼성 브라우저에서 실제 본문이 이동했고 `frame=true changed=true`, `postcondition=true`를 확인했다(`scroll-panel-fixed.log`, `scroll-panel-fixed.png`). 이 검사는 한 번의 스와이프 후 중단해 후속 모델 조작과 분리했다.

에뮬레이터 회귀 13개 통과는 안내 패널의 터치 속성 변경 전 결과다. 안내 패널이 겹친 시작점을 포함하도록 보강한 기기 시험은 재실행 시 접근성 서비스 재연결/시험 Activity 시작 단계에서 실패해 결과를 얻지 못했다. 해당 단계의 실패와 실제 삼성 브라우저 명령 검증은 별도로 기록했다. 초기 실패 로그도 보존했다.

설치 APK와 로컬 APK의 SHA-256이 `d1b73fb28a797d7ad700bd63d99d871c7005d21f1b1a5eba36f83249bce23ba2`로 일치했다. 비교 결과는 `app/build/reports/browser-reliability-qa/installed-apk-final.json`에 보관한다. 최종 APK 설치 뒤 실행한 위 스크롤 검사는 완료 상태까지 통과했다.

## 범위

실제 예매·결제 확정, 인증정보 입력, 다른 제조사와 모든 앱 버전의 임의 명령 성공률은 이 시험의 결과에 포함하지 않는다. 접근성 본문을 제공하지 않는 앱에서는 이미지 관찰 비용과 기존 제한이 적용된다. Android 13 미만에서는 새 접근성 입력기 API를 사용할 수 없어 기존 접근성 Enter 기능에 의존한다.

## 후속 원인 분석: 과도한 확인과 웹 접근성

2026-09-12 후속 수정. 아래 기록은 앞 절의 이전 APK 검증과 구별한다. 증거는 `app/build/reports/approval-web-qa/`에 있다.

**확인 요청의 원인.** 실제 실행 검증기가 버튼의 동작 대신 모델 설명·리소스 ID까지 합친 문자열과 전체 과제의 위험도를 검사했다. 예약 준비 클릭도 설명에 ‘예약 확정’이 있으면 확인 대상이 됐고, 내부 ID의 `confirm`도 영향을 줬다. 별도의 음식 주문 규칙은 아직 구매하지 않는 식당 탐색에서 선택 허락을 요구했다. 이 규칙을 제거하고 관찰한 컨트롤 라벨·자식 라벨·확정 안내로 판단한다. 초안 입력과 검색 제출은 최종 전송과 구별하며, 실제 전송·삭제·예약 확정 및 결제·보안 차단 경계는 유지한다.

**삼성 브라우저의 원인.** SM-F731N / Android API 37 / Samsung Internet 30.0.2.61의 `example.org` 화면에서 Sonju는 33개 가시 노드와 큰 `SurfaceView`를 읽었지만 본문과 링크 노드는 받지 못했다. 트리가 잘린 상태는 아니었다. 캐시 초기화, 전체 접근성 이벤트 구독, APK 재설치로도 같은 결과였다.

설치된 공개 브라우저 APK의 `com.sec.terrace.content.browser.TinContentView`를 확인했다. `isAllowedPackage`는 활성 접근성 서비스의 구형 웹 접근성 capability 비트 4 또는 `TinTerraceInternals.getAXWhiteListNames`의 패키지 일치를 검사한다. `getAccessibilityNodeProvider`는 결과를 `mAxAllowed`에 보관하고 거짓이면 웹 공급자를 반환하지 않는다. 관찰 결과와 이 분기가 일치하며, Sonju의 노드 순회 한도나 특정 사이트의 HTML 문제로 설명되지 않는다. 구형 `canRequestEnhancedWebAccessibility` 선언을 추가해도 이 기기의 서비스 capability는 161로 같았고 본문도 생기지 않았다. 효과 없는 선언과 임시 이벤트 변경은 최종 소스에서 제거했다.

**손주에서 바꾼 처리.** 브라우저를 지정하지 않은 새 웹 탐색은 실제 설치 목록에 있는 Chrome을 우선한다. 특정 브라우저나 현재 페이지를 요청한 경우에는 해당 앱을 유지한다. 현재 웹페이지 읽기는 접근성 WebView의 본문을 먼저 읽고, 본문이 노출되지 않은 경우 기존 기기 내 Korean OCR을 사용한다. OCR 결과는 읽기 전용이며 실행 노드나 모델 입력으로 합성하지 않는다. 긴 WebView 문단을 버튼 라벨처럼 120자로 자르던 수집도 4,000자로 늘렸다. 계획기용 compact 라벨 길이는 그대로다.

**실제 명령 경로의 추가 실패.** 본문 누락 감지·캡처·시각 좌표는 같은 전체 화면 기준을 사용해야 한다. 서비스는 레이아웃용 리소스 크기 대신 시스템 바를 포함하는 `WindowManager.maximumWindowMetrics`를 사용하고, 디버그 관찰에도 같은 기준을 적용했다([Android 계약](https://developer.android.com/reference/android/view/WindowManager#getMaximumWindowMetrics())). 이어진 실기기 검사는 화면 변경으로 취소된 캡처가 같은 캐시 관찰을 6번 재사용하는 문제를 재현했다. 계획용 캐시는 최근 화면이면 재사용할 수 있지만, OCR은 정확한 현재 화면 번호를 요구한다. 오래된 관찰 때문에 OCR이 취소됐을 때는 캐시를 무효화한 뒤 다시 수집하도록 수정했다. 화면 변경 검증을 느슨하게 만들지 않았다.

삼성 실기기에서 `현재 웹페이지 읽어줘`를 다시 실행하자 첫 화면 변경 뒤 새로운 관찰로 재시도하고, 로컬 인식 9줄을 얻어 Example Domain의 제목·본문·Learn more를 안내했다. 명령 전달부터 안내까지 약 3.4초였고, 원격 모델 호출·실행 동작·확인창은 없었다. `samsung-local-read-final.log`와 `samsung-local-read-final.png`에 실행 경로와 실제 안내를 보관한다. 초기 실패도 `samsung-read-flow.log`, `samsung-fixed-flow.log`에 남겼다.

같은 기기의 Chrome 152.0.7977.82에서 같은 `example.org` 페이지를 비교했다. 가시 노드 28개에 WebView, Example Domain 제목, 본문 문단, `CLICK` 동작을 가진 Learn more 링크가 실제로 포함됐다. `tree-truncated=false`, `rendered-content-unavailable=false`였다. 같은 명령은 약 2.9초 만에 `source=accessibility`로 안내했으며 OCR·원격 모델 호출·확인창은 없었다. `chrome-example-before.xml`, `chrome-native-read.log`, `chrome-native-read.png`, `chrome-native-read-result.json`에 관찰·실행·화면 증거를 보관한다. 삼성의 본문 누락과 Chrome의 네이티브 본문 제공을 동일 기기·동일 공개 페이지에서 확인한 결과다.

이는 삼성의 네이티브 접근성 공급자를 복구하거나 모든 웹 요소를 접근성 버튼으로 만드는 변경이 아니다. 해당 브라우저의 공급자 제한은 브라우저 측 변경이 필요하다. 손주는 실제 접근성 본문과 로컬 문자 인식을 구분하고, 보이지 않는 내용을 완료 근거로 꾸미지 않는다. OCR은 현재 보이는 텍스트 읽기를 해결하며 이미지 의미 해석·화면 밖 내용·인증 조작을 보장하지 않는다.

### 후속 수정의 최종 검증

| 검사 | 현재 결과 |
| --- | --- |
| 단위 테스트 | 총 304개 중 300개 통과, 선택 실행 4개 제외, 실패·오류 0 |
| 빌드 | 앱 APK와 AndroidTest APK 생성 성공 |
| Android Lint | 오류 0, 기존 경고 81 |
| 최종 APK 설치 | 설치 성공, 로컬·설치 APK SHA-256 일치: `4356ba6a287f2a50ebc62562da30c2ce41dcd56e1cab166a21b0e806ba44eb37` |
| 최종 APK 삼성 본문 읽기 | 제목·문단·링크 문구를 실제 안내창에서 확인. `samsung-local-read-installed.log`, `samsung-local-read-installed.png` |
| Chrome 네이티브 본문 비교 | 성공. 본문·링크를 접근성으로 수집하고 같은 읽기 명령을 약 2.9초에 안내. OCR·원격 모델 호출·확인창 0 |

집계·설치 해시는 `verification.json`에 보관했다. 브라우저 실행은 자동 승인 검토에서 거부되어 사용자가 두 브라우저에서 공개 페이지를 직접 열었고, 열린 페이지의 읽기 명령은 Sonju의 실제 실행 경로로 검증했다. 비교 검증은 모두 끝났다. 이번 검증은 음성 인식 이후 명령 처리 경로이며 마이크 인식률이나 실제 전송·결제 실행 시험이 아니다.
