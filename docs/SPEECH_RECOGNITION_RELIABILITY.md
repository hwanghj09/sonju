# 한국어 음성 인식 개선 — 2026-09-25

음성 입력은 Android 음성 인식 API만 사용한다. OpenAI 음성 전사, 유료 STT SDK,
인식 문장을 LLM으로 다시 쓰는 처리는 포함하지 않는다. 기존 명령 계획용 OpenAI 호출은
이번 음성 인식 변경 범위와 별개이며 변경하지 않았다.

## 확인한 원인

삼성 SM-F731N / Android 17에서 기본 인식기와 기기 내 인식기가 달랐다.

- 기존 `createSpeechRecognizer()`의 기본 서비스:
  `com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService`
- `createOnDeviceSpeechRecognizer()`의 기기 내 서비스:
  `com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService`

같은 한국어 합성 음성을 PC 스피커에서 재생해 휴대폰 마이크로 입력했다.
발화는 “유튜브에서 임영웅 노래 검색해 줘”였다.

| 경로 | 실제 최종 결과 |
| --- | --- |
| 기존 기본 엔진/설정 | 유튜브에서 **이명** 노래 검색해 줘 |
| 기본 엔진 + 새 설정 | 유튜브에서 임영웅 **[노래 누락]** 검색해 줘 |
| 기기 내 엔진 + 새 설정 | 유튜브에서 임영웅 노래 검색해 줘 |
| 최종 앱의 공통 인식 경로, 반복 1 | 유튜브에서 임영웅 노래 검색해 줘 |
| 최종 앱의 공통 인식 경로, 반복 2 | 유튜브에서 임영웅 노래 검색해 줘 |

기존 오인식에도 0.847~0.867의 신뢰도가 반환됐다. 신뢰도 점수만으로 단어를
교정하거나, 예상 명령에 맞춰 다른 단어로 바꾸는 방법은 사용하지 않았다.
이 비교는 한 합성 발화의 재현 결과이며 사람 전체의 인식률 통계가 아니다.
사용자가 이전에 틀리게 인식했던 실제 발화 원본은 제공되지 않았다.

코드에서도 별도 문제가 확인됐다. 홈과 패널의 인식 설정이 달랐고 앱 이름 힌트가
없었다. 패널은 결과마다 인식기를 제거하고 100ms 뒤 다시 만들어 발화 사이에 녹음
공백을 만들었다. 문자열을 이어 붙이며 반복 단어를 지울 수 있었고, 뒤쪽 인식이 실패하면
앞에서 확정된 문장 조각만 실행할 수 있었다. 호출어 중지는 비동기 서비스 Intent를
보낸 뒤 고정 시간만 기다렸으며, 두 TTS 인스턴스의 안내 음성도 함께 중지하지 않았다.

## 수정

- `CommandSpeechRecognizer`가 사용 가능한 기기 내 인식기를 먼저 사용한다.
  듣기 준비 전에 한국어 미지원/모델 미설치 오류가 오면 기존 Android 기본 인식기로
  한 번 전환한다. 음성이 들어오기 시작한 뒤에는 자동 전환하지 않는다.
- 홈/접근성 패널이 `CommandRecognition`의 한국어 설정, 설치 앱 이름 힌트,
  부분 결과, 최대 5개 후보 및 무음 설정을 함께 사용한다.
- 지원하는 Android 13 이상 엔진에는 segmented session을 요청하고,
  같은 인식기를 유지한 채 세그먼트를 받는다. 중간 결과는 자막으로만 표시하고
  세션 종료 후 최종 결과를 한 번만 전달한다. 미지원 엔진의 일반 최종 결과도 처리한다.
- 반복해서 말한 단어를 지우지 않는다. 오류·시간 초과·취소 시 문장 조각을 실행하지
  않으며, 이전 엔진/세션에서 늦게 온 결과를 무시한다. 긴 결과도 잘라서 실행하지 않는다.
- 호출어 녹음을 같은 메인 스레드에서 먼저 중지/해제하고 명령 인식을 시작한다.
  앱/접근성 서비스의 안내 음성이 새 음성 입력에 섞이지 않도록 중지한다.

기기 내 인식기에는 음성 전사 API 사용료가 없다. 기본 인식기로 전환된 경우에는 기존처럼
해당 기기 음성 서비스의 처리 방식이 적용될 수 있다. 손주는 별도 유료 STT를 호출하지 않는다.

## 검증

- `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug` 통과.
- JVM: 343개 통과, 선택 항목 10개 제외. Lint: 오류 0개, 기존 경고 83개.
- 삼성 기기 콜백/UI 시험 8개 통과: 세그먼트와 반복 단어, 부분 결과의 수정,
  뒤쪽 오류, 일반 결과, 빈/긴 결과, 엔진 전환 뒤 늦은 응답, 취소/백그라운드 정리 등.
- 같은 합성 발화는 기기 내 엔진 비교 1회와 최종 앱 경로 2회에서 모두 정확했다.
- 설치 후 사용자가 직접 “부산으로 가는 기차표 예매해줘”를 포함한 여러 문장을 말했고,
  모두 정확하게 받아 적혔다고 확인했다. 이는 사용자의 실제 발화 확인이며, 기차표
  예매 동작을 실행하거나 완료했다는 뜻은 아니다.
- 기존 데이터를 보존하는 `adb install -r`로 설치했다. 설치 APK와 로컬 APK의 SHA-256:
  `ff07a2b131aa1732e35dc86e6ddbb15b7d5250fcb0e96ea584c2bc93cfd31125`.
- 기기의 손주 접근성이 꺼져 있어 실제 접근성 패널 조작/전체 명령 실행은 재검증하지
  않았다. 홈과 패널은 같은 인식 구현을 사용하지만 이 사실이 전체 실행 검증을 대신하지는 않는다.

휴대폰 자체 스피커 재생 시험과 Android 외부 오디오 주입 시험에서는 유효한 비교
결과를 얻지 못했다. 정확도 비교에는 PC 스피커 → 휴대폰 마이크 경로만 사용했다.
다양한 방언·소음과 다른 기기의 한국어 모델/세그먼트 지원은 별도 확인이 필요하다.
중간 자막은 최종 결과에서 수정될 수 있다.

## 재실행

디버그 앱과 테스트 APK를 `adb install -r`로 설치한 뒤 휴대폰을 PC 스피커 가까이 둔다.
테스트 음원은 Samsung TTS로 생성한 합성 음성이다. 실제 명령은 실행하지 않는다.

```powershell
./scripts/verify-speech-recognition.ps1 -Baseline
./scripts/verify-speech-recognition.ps1 -Repeat 2
```

결과는 `build/speech-accuracy/`에 기록된다. 음성 전사 API 키를 사용하지 않는다.
일반 콜백/UI 시험은 다음처럼 앱 삭제 없이 실행한다.

```text
adb shell am instrument -w -r -e class com.hwanghj09.sonju.CommandRecognitionDeviceTest,com.hwanghj09.sonju.ListeningUiDeviceTest com.hwanghj09.sonju.test/androidx.test.runner.AndroidJUnitRunner
```

플랫폼 계약: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer),
[RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent),
[RecognitionListener](https://developer.android.com/reference/android/speech/RecognitionListener).
