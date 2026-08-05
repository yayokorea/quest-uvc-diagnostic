# Quest UVC Diagnostic

Quest 2에서 Android USB Host 열거, UVC descriptor, Probe/Commit, Bulk/Isochronous MJPEG 수신을 검증하는 진단 앱입니다. PC 전송이나 눈 추적 기능은 포함하지 않습니다.

## 구성

- Kotlin/Compose: 장치 탐색, USB 권한, descriptor 분석, 모드 선택, 통계/미리보기/보고서
- C++/JNI + libusb 1.0.29: 승인된 usbfs 파일 디스크립터 래핑, Probe/Commit, 비동기 Bulk/Isochronous 전송
- ABI: `arm64-v8a`, minSdk 29, compile/targetSdk 35

`third_party/libusb`는 LGPL-2.1-or-later 라이선스의 upstream v1.0.29 소스입니다. 배포 시 해당 라이선스와 소스 제공 의무를 확인하십시오.

## 빌드

Android Studio에서 SDK 35, NDK 27 이상, CMake 3.22.1을 설치하고 프로젝트를 연 뒤 `assembleDebug`를 실행합니다.

```text
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

카메라 연결 때문에 USB ADB를 쓸 수 없다면 먼저 Wi-Fi ADB를 설정합니다.

```text
adb tcpip 5555
adb connect QUEST_IP:5555
```

## 실기기 시험

1. 앱을 실행하고 일반 UVC 웹캠을 연결합니다.
2. 목록에서 장치를 선택하고 Quest의 USB 권한 창을 승인합니다.
3. VideoControl/VideoStreaming 인터페이스와 format, resolution, FPS, endpoint alternate setting을 확인합니다.
4. 저대역폭 MJPEG 모드로 `Probe & start`를 누릅니다. 실패하면 packet size가 더 큰 alternate setting을 선택해 재시도합니다.
5. Received/Packets/Frames/FPS가 증가하고 preview가 보이는지 확인합니다.
6. `Save current JPEG`와 `Share report`로 증거를 저장합니다.
7. 같은 절차로 OpenIris ESP32-S2 장치를 시험합니다.

성공 판정은 권한 승인 및 연결, VC/VS 탐지, Probe/Commit 성공, 실제 USB 패킷 수신, 정상 JPEG 프레임과 0보다 큰 FPS입니다. Isochronous가 Quest 커널에서 거부되면 오류 코드를 호환성 실패 결과로 기록합니다.

## 알려진 범위

- 여러 장치를 열거하지만 한 번에 한 스트림만 엽니다.
- 자동 재연결, 백그라운드 실행, 두 카메라 동시 스트리밍은 없습니다.
- UVC 1.0/1.1의 26/34바이트 Probe block을 순서대로 시도합니다.
- 장치가 잘못된 descriptor 또는 비표준 Probe 응답을 사용하면 보고서의 raw 오류를 기준으로 장치별 quirk가 필요할 수 있습니다.
