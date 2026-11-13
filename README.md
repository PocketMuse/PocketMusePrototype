# PocketMusePrototype

Android 프로토타입에서 Civitai 체크포인트를 탐색하고 내려받는다. 체크포인트와 공식 SD 1.5 LCM LoRA가 모두 검증되면 기기에서 정적 LCM 병합과 HQQ 변환을 예약하고 Library에 결과를 표시한다. [변환과 JNI 계약](docs/Converter.md)

Market에서 체크포인트 다운로드를 요청하면 공용 SD1.5 LCM LoRA도 별도 작업으로 준비한다. 진행률, 실패 재시도 및 삭제는 Library에서 확인할 수 있다. [공용 LoRA 다운로드 계약](docs/Model-Downloads.md#공용-sd15-lcm-lora)

## 빌드와 테스트

JDK 21, Android SDK 36.1, NDK 27.1.12297006, CMake 3.22.1 및 Platform-Tools를 준비한다. APK는 `arm64-v8a`용이며 최소 Android 10(API 29)에서 실행한다. CMake는 첫 구성에서 SHA-512가 고정된 nlohmann/json 3.12.0과 PicoSHA2 1.0.1 소스를 가져온다.

Windows 프로젝트 루트에서 실행한다.

```powershell
git submodule update --init third_party/to-mnn
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

계측 테스트에는 연결된 ARM64 기기가 필요하다. 합성 Converter 테스트는 앱 캐시에 체크포인트와 템플릿을 만든다. [모델 다운로드와 Library](docs/Model-Downloads.md)

## 실제 정적 LCM 추론 스모크

기존 앱에서 다운로드와 정적 LCM 변환을 완료한 ToonYou 모델을 사용한다. 기기에 설치된 앱과 빌드 소스의 Room 스키마 버전 및 APK 서명이 일치해야 한다. 이 저장소의 `main`은 Room v3이다. Room v4 기기는 v4가 포함된 앱 소스에 Runtime 변경을 적용해 빌드한다. [Runtime 호출 계약과 실측](docs/Runtime.md)

Windows Git Bash에서 고정된 `third_party/to-mnn` 체크아웃을 준비한다. `VCPKG_ROOT`, Visual Studio C++ 도구, Ninja 및 위와 동일한 NDK의 `ANDROID_NDK_HOME`이 필요하다.

```bash
git submodule update --init third_party/to-mnn
cd third_party/to-mnn
uv python install 3.12
uv sync --locked
bash scripts/sd15.sh --prepare-tokenizer
bash scripts/build-android.sh
```

앱 프로젝트 루트의 PowerShell에서 실행한다. `$nativeRoot`는 동일한 고정 리비전으로 빌드한 별도 체크아웃의 경로로도 지정할 수 있다. `$key`는 기기 Library에 등록된 ToonYou의 `modelVersionId_fileId`로 설정한다. 여러 기기가 연결되어 있다면 `ANDROID_SERIAL`로 대상을 지정한다.

```powershell
$nativeRoot = (Resolve-Path 'third_party/to-mnn').Path
$key = '125771_90840'
$pkg = 'com.example.pocketmuseprototype'
$runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
$class = "$pkg.runtime.StaticLcmSmokeTest"
.\gradlew.bat "-Psd15RuntimeRoot=$nativeRoot" :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb shell run-as $pkg mkdir -p files/runtime-smoke/tokenizer
foreach ($name in 'vocab.json','merges.txt') {
    $stage = "/data/local/tmp/pocketmuse-runtime-$([Guid]::NewGuid().ToString('N'))"
    adb push (Join-Path $nativeRoot "artifacts/sd15/tokenizer/$name") $stage
    if ($LASTEXITCODE -ne 0) { throw "Tokenizer upload failed" }
    try {
        adb shell "run-as $pkg sh -c 'cat $stage > files/runtime-smoke/tokenizer/$name'"
        if ($LASTEXITCODE -ne 0) { throw "Tokenizer copy failed" }
    } finally {
        adb shell rm $stage
    }
}

adb shell am instrument -w -r -e class "$class#generatesAndDecodesPng" -e staticLcmSmoke true -e storageKey $key $runner
adb logcat -d -s StaticLcmSmoke:I
```

`png=` 로그의 `runtime-smoke/results/…png` 경로를 사용해 회수한다. 아래 리디렉션은 PowerShell 7.4 이상에서 바이너리를 보존한다.

```powershell
$relativePng = 'runtime-smoke/results/<filename>.png'
adb exec-out run-as $pkg cat "files/$relativePng" > toon-you-static-lcm.png

adb shell am instrument -w -r -e class "$class#reportsInitializationErrorAndPreCancellation" -e runtimeContract true $runner
adb shell am instrument -w -r -e class "$class#propagatesCallbackFailureAndCancelsAfterFirstDenoisingStep" -e staticLcmCancelSmoke true -e storageKey $key $runner
```

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

공용 LoRA가 없는 격리 패키지에 4,265,146,304바이트의 `v1-5-pruned-emaonly.safetensors`와 공식 SD 1.5 LCM LoRA를 준비한다. 테스트는 입력의 SHA-256을 확인한 뒤 체크포인트 공개 직후 상태를 만든다. LoRA 대기, 자동 WorkManager 예약, 변환 중 취소와 공용 LoRA 삭제, 재준비, 홈 화면에서 변환 완료, CLI 출력 해시 비교를 검증한다. 기기 화면을 잠금 해제한 상태에서 실행한다.

```powershell
.\gradlew.bat -PconverterBenchmark=RelWithDebInfo :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

$pkg = 'com.example.pocketmuseprototype.converterbenchmark'
$remote = "/sdcard/Android/data/$pkg/files/models/90301_90302/source"
adb shell mkdir -p "$remote"
adb push 'C:\path\to\v1-5-pruned-emaonly.safetensors' "$remote/checkpoint.safetensors"
adb shell mkdir -p "/sdcard/Android/data/$pkg/files/converter-smoke"
adb push 'C:\path\to\lcm-lora-sdv1-5.safetensors' "/sdcard/Android/data/$pkg/files/converter-smoke/lcm-lora.safetensors"
adb shell am instrument -w -e class 'com.example.pocketmuseprototype.conversion.BackgroundConversionAndroidTest#realCheckpointCancelsThenCompletesInBackground' -e realBackgroundConversion true "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
```

공식 LoRA를 준비한 상태에서 JNI 오류 후에도 공용 파일을 다시 사용할 수 있는지 확인한다.

```powershell
adb shell am instrument -w -e class 'com.example.pocketmuseprototype.conversion.BackgroundConversionAndroidTest#staticNativeFailureReleasesTheOfficialLora' -e verifiedStaticFixture true "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
```

완료 후 격리 앱을 다시 열어 Library에서 **Conversion fixture**가 **내 모델**에 **정적 LCM**으로 표시되는지 확인한 뒤 삭제한다. 다시 시험하기 전에 격리 앱의 공용 LoRA도 삭제한다. 원본, 출력과 LoRA 사본에 약 5.9 GB가 필요하다.
