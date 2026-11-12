# PocketMusePrototype

Android 프로토타입에서 Civitai 체크포인트를 탐색하고 내려받는다. 다운로드 검증이 끝나면 기기에서 SD 1.5 HQQ 변환을 예약하고 Library에 결과를 표시한다. [변환과 JNI 계약](docs/Converter.md)

## 빌드와 테스트

JDK 21, Android SDK 36.1, NDK 27.1.12297006, CMake 3.22.1 및 Platform-Tools를 준비한다. APK는 `arm64-v8a`용이며 최소 Android 10(API 29)에서 실행한다. CMake는 첫 구성에서 SHA-512가 고정된 nlohmann/json 3.12.0과 PicoSHA2 1.0.1 소스를 가져온다.

Windows 프로젝트 루트에서 실행한다.

```powershell
git submodule update --init third_party/to-mnn
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

계측 테스트에는 연결된 ARM64 기기가 필요하다. 합성 Converter 테스트는 앱 캐시에 체크포인트와 템플릿을 만든다. [모델 다운로드와 Library](docs/Model-Downloads.md)

## 실제 HQQ 모델 스모크

로컬에 이미 준비한 SD 1.5 safetensors와 호환되는 HQQ 템플릿을 사용한다. `adb`가 여러 기기를 감지하면 `ANDROID_SERIAL`로 테스트 기기를 선택한다. Gradle의 기본 계측 실행은 종료할 때 테스트 앱과 대상 앱을 제거할 수 있으므로, 모델 입력을 준비한 뒤에는 스모크를 ADB로 직접 실행한다.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

$checkpoint = 'C:\path\to\model.safetensors'
$template = 'C:\path\to\template'
$remote = '/sdcard/Android/data/com.example.pocketmuseprototype/files/converter-smoke'
adb shell mkdir -p "$remote/template"
adb push "$checkpoint" "$remote/model.safetensors"
adb push (Join-Path $template 'graph.bin') "$remote/template/graph.bin"
adb push (Join-Path $template 'manifest.json') "$remote/template/manifest.json"
adb shell am instrument -w -r -e class com.example.pocketmuseprototype.conversion.ConverterSmokeTest -e converterSmoke true com.example.pocketmuseprototype.test/androidx.test.runner.AndroidJUnitRunner
```

`$checkpoint`와 `$template`은 실제 로컬 경로로 바꾼다. 출력은 기기에 수 GiB의 여유 공간이 필요하다. 테스트는 결과와 출력 파일 목록·크기를 확인한 뒤 이번 실행의 출력 디렉터리를 정리한다. 입력은 다음 실행에 재사용할 수 있다.

정적 LCM 변환 스모크는 같은 체크포인트와 호환 템플릿에 로컬의 공식 SD 1.5 LCM-LoRA 파일을 추가해 실행한다. 동일 입력으로 생성한 CLI 기준 해시가 있으면 `sha256sum *.mnn` 형식의 `reference-lcm-sha256.txt`를 입력 디렉터리에 두어 출력 파일 세 개의 SHA-256도 비교할 수 있다.

```powershell
adb push 'C:\path\to\lcm-lora-sdv1-5.safetensors' "$remote/lcm-lora.safetensors"
adb shell am instrument -w -r -e class com.example.pocketmuseprototype.conversion.ConverterSmokeTest -e converterLcmSmoke true com.example.pocketmuseprototype.test/androidx.test.runner.AndroidJUnitRunner
```

기존 앱 설치를 유지하며 두 스모크를 실행하려면 [격리 패키지 빌드와 실행 방법](docs/Converter.md#네이티브-빌드-성능-재현)을 따른다.

## 실제 백그라운드 변환 스모크

격리 패키지에 4,265,146,304바이트의 `v1-5-pruned-emaonly.safetensors`를 준비한다. 테스트는 이미 검증된 다운로드의 공개 직후 상태를 만들어 실제 WorkManager와 APK 내장 템플릿을 사용한다. 변환 중 취소한 뒤 다시 시작하고, 기기를 홈 화면으로 전환해 완료를 기다린다. 기기 화면을 잠금 해제한 상태에서 실행한다.

```powershell
.\gradlew.bat -PconverterBenchmark=RelWithDebInfo :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

$pkg = 'com.example.pocketmuseprototype.converterbenchmark'
$remote = "/sdcard/Android/data/$pkg/files/models/90301_90302/source"
adb shell mkdir -p "$remote"
adb push 'C:\path\to\v1-5-pruned-emaonly.safetensors' "$remote/checkpoint.safetensors"
adb shell am instrument -w -e class 'com.example.pocketmuseprototype.conversion.BackgroundConversionAndroidTest#realCheckpointCancelsThenCompletesInBackground' -e realBackgroundConversion true "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
```

완료 후 격리 앱의 Library에서 결과를 확인하고 **Conversion fixture**를 삭제한다. 한 번의 시험에는 원본과 출력에 약 5.6 GB가 필요하다.
