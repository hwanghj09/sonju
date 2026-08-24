# USB ADB 기기 테스트

## 최초 연결

1. 폰에서 개발자 옵션을 활성화합니다.
2. 개발자 옵션에서 `USB 디버깅`을 켭니다.
3. USB 케이블로 PC에 연결하고 폰의 `이 컴퓨터에서 USB 디버깅을 허용` 대화상자를 승인합니다.
4. 저장소 루트에서 아래 명령을 실행합니다.

```powershell
.\scripts\adb-debug-install.ps1
```

스크립트는 SDK의 `adb.exe`를 찾고, 연결된 기기를 확인하고, 한글 경로의 Gradle 문제를 피하기 위해 임시 ASCII 드라이브에서 debug APK를 빌드한 뒤 설치하고 Sonju를 실행합니다. 임시 드라이브는 실행 후 제거됩니다.

기기가 여러 대면 serial을 지정합니다.

```powershell
.\scripts\adb-debug-install.ps1 -Serial R58MXXXXXXX
```

이미 APK를 빌드했다면 빌드를 생략할 수 있습니다.

```powershell
.\scripts\adb-debug-install.ps1 -SkipBuild
```

## 테스트 전 확인

- 온보딩과 개인정보 고지를 완료합니다.
- Android 설정에서 `Sonju 화면 도우미` 접근성 서비스를 직접 켭니다. ADB secure settings를 강제로 쓰는 방식은 실제 사용자 활성화와 동작이 다를 수 있어 테스트 근거로 사용하지 않습니다.
- 다른 앱 위에서 Sonju 플로팅 버튼을 호출하고, 안전한 테스트 앱에서 앱 열기·탭·검색어 입력·스크롤을 확인합니다.
- 결제·개인정보 입력 테스트에는 실제 계정이나 결제수단을 사용하지 않습니다.

## 연결 진단

```powershell
$adb = "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb -s <serial> shell getprop ro.product.model
```

`unauthorized`이면 폰 화면의 허용 대화상자를 승인합니다. `offline`이면 USB 케이블을 다시 연결하고 USB 디버깅을 껐다 켠 뒤 `adb kill-server; adb start-server`를 실행합니다.

현재 연결된 기기가 없으면 스크립트는 설치를 시작하지 않고 원인과 다음 조치를 출력합니다.
