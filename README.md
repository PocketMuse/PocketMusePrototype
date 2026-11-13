# PocketMusePrototype

Android 프로토타입에서 Civitai 체크포인트를 탐색하고 내려받는다. 체크포인트와 공식 SD 1.5 LCM LoRA가 모두 검증되면 기기에서 정적 LCM 병합과 HQQ 변환을 예약하고 Library에 결과를 표시한다. [변환과 JNI 계약](docs/Converter.md)

Market에서 체크포인트 다운로드를 요청하면 공용 SD1.5 LCM LoRA도 별도 작업으로 준비한다. 진행률, 실패 재시도 및 삭제는 Library에서 확인할 수 있다. [공용 LoRA 다운로드 계약](docs/Model-Downloads.md#공용-sd15-lcm-lora)

Chat에서 Library의 최적화된 정적 LCM 모델을 선택하고 프롬프트를 전송하면 기기 OpenCL GPU에서 512×512 이미지를 생성한다. 스텝은 2·4·6·8 중 선택하며 기본값은 4다. 앱이 번들 Tokenizer를 자동으로 준비하고, Runtime이 매 요청의 seed를 선택한다. 다른 탭으로 이동해도 작업이 계속되며 최신 요청과 결과 한 장은 화면 상태로만 유지한다. [Chat 생성 계약](docs/Runtime.md#chat-이미지-생성)

## 빌드와 테스트

JDK 21, Android SDK 36.1, NDK 27.1.12297006, CMake 3.22.1 및 Platform-Tools를 준비한다. APK는 `arm64-v8a`용이며 최소 Android 10(API 29)에서 실행한다. CMake는 첫 구성에서 SHA-512가 고정된 nlohmann/json 3.12.0과 PicoSHA2 1.0.1 소스를 가져온다.

일반 Debug·Release APK는 Runtime을 포함한다. 아래 절차로 네이티브 의존성을 준비한 뒤 Windows 프로젝트 루트에서 실행한다. Converter 전용 개발과 CI에는 `-PconverterOnly=true`를 명시할 수 있다. 이 모드는 MNN·ICU 준비 없이 기존 Converter를 빌드한다.

```powershell
git submodule update --init third_party/to-mnn
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

계측 테스트에는 연결된 ARM64 기기가 필요하다. 테스트 종료 후 기존 설치가 제거될 수 있으므로, 모델이 저장된 기기에서는 아래의 `adb install -r`과 `am instrument`를 사용한다. [모델 다운로드와 Library](docs/Model-Downloads.md)

## Runtime 빌드 준비

Windows Git Bash에서 고정된 `third_party/to-mnn` 체크아웃을 준비한다. `VCPKG_ROOT`, Visual Studio C++ 도구, CMake·Ninja 및 앱과 동일한 NDK의 `ANDROID_NDK_HOME`이 필요하다. 기존 to-mnn 스크립트로 검증된 MNN 엔진을 빌드한다.

```bash
git submodule update --init third_party/to-mnn
cd third_party/to-mnn
uv python install 3.12
uv sync --locked
bash scripts/sd15.sh --prepare-tokenizer
bash scripts/build-android.sh --mnn-only
```

`third_party/to-mnn`에서 아래 PowerShell 명령으로 MNN에 16 KiB 링크 옵션을 적용하고 새 빌드 영수증을 기록한다. 기존 4 KiB 영수증은 별도 이름으로 보존한다. 이 단계는 기존 4 KiB 엔진을 처음 준비했을 때 한 번 실행한다. [Android 16 KiB ELF 가이드](https://developer.android.com/guide/practices/page-sizes#compile-r27-lower)

```powershell
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$engine = 'MNNRuntime/upstream-cda4a6f'
$build = 'MNNRuntime/build-android-arm64-api28-cda4a6f'
$verify = @('-B', '-m', 'scripts.tools.verify_mnn_android', '--source', $engine,
    '--runtime', $build, '--ndk', $env:ANDROID_NDK_HOME, '--abi', 'arm64-v8a', '--api', '28', '--stl', 'c++_shared')
& .venv/Scripts/python.exe @verify
cmake -S $engine -B $build '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
cmake --build $build --target MNN --parallel
Move-Item -LiteralPath "$build/engine-build.json" -Destination "$build/engine-build-4k.json"
& .venv/Scripts/python.exe @verify --record-build
```

같은 체크아웃의 Git Bash에서 `bash scripts/build-android.sh --project-only`를 실행해 Android vcpkg ICU와 CMake 빌드 설정을 준비한다. 앱의 Gradle 빌드는 검증된 to-mnn 소스에서 Runtime을 직접 컴파일한다. 같은 고정 리비전의 별도 체크아웃을 사용하려면 `-Psd15RuntimeRoot=<경로>`를 지정한다. [빌드 검증 계약](docs/Runtime.md#빌드와-호출-계약)

## 실제 정적 LCM 추론 스모크

이미 준비된 ToonYou Beta 6가 있는 Galaxy S20+에서 Chat 경로를 검증하려면 다음과 같이 APK를 업데이트하고 계측 테스트를 실행한다. 이 테스트는 2스텝 요청의 첫 스텝에서 취소한 뒤, 탭 이동과 Activity 재생성을 거쳐 4스텝 이미지가 화면에 표시되는지 확인한다.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$pkg = 'com.example.pocketmuseprototype'
adb shell am instrument -w -r -e class "$pkg.runtime.ChatGenerationAndroidTest" -e chatModelName ToonYou "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
```

아래 Runtime 전용 스모크는 별도의 수동 Tokenizer 경로를 사용한다.

기존 앱에서 다운로드와 정적 LCM 변환을 완료한 ToonYou 모델을 사용한다. 앱은 Room v4를 사용하고 v1부터의 마이그레이션을 등록한다. 기존 설치와 같은 서명의 APK를 `adb install -r`로 설치한다. [Runtime 호출 계약과 실측](docs/Runtime.md)

앱 프로젝트 루트의 PowerShell에서 실행한다. `$key`는 Library에 등록된 모델의 `modelVersionId_fileId`다. 여러 기기가 연결되어 있다면 `ANDROID_SERIAL`로 대상을 지정한다.

```powershell
$nativeRoot = (Resolve-Path 'third_party/to-mnn').Path
$key = '125771_90840'
$pkg = 'com.example.pocketmuseprototype'
$runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
$class = "$pkg.runtime.StaticLcmSmokeTest"
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
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

Cold/Warm을 재현할 때는 새 `cacheRun` UUID로 테스트 전용 캐시를 선택한다. Cold는 경로가 비어 있어야 하며 Warm은 같은 UUID를 사용한다. 각각의 `am instrument` 호출에서 PID를 확인하고, 실행 중 다른 터미널에서 `adb shell dumpsys meminfo <pid>`로 PSS·RSS를 표본 수집할 수 있다.

```powershell
$run = [Guid]::NewGuid().ToString()
$smoke = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', "$class#generatesAndDecodesPng",
    '-e', 'staticLcmSmoke', 'true', '-e', 'storageKey', $key, '-e', 'cacheRun', $run)
adb @smoke -e expectColdCache true $runner
adb @smoke $runner
adb @smoke $runner
adb logcat -d -s StaticLcmSmoke:I
```

`png=` 로그의 `runtime-smoke/results/…png` 경로를 사용해 회수한다. 아래 리디렉션은 PowerShell 7.4 이상에서 바이너리를 보존한다.

```powershell
$relativePng = 'runtime-smoke/results/<filename>.png'
adb exec-out run-as $pkg cat "files/$relativePng" > toon-you-static-lcm.png

adb shell am instrument -w -r -e class "$class#reportsInitializationErrorAndPreCancellation" -e runtimeContract true $runner
adb shell am instrument -w -r -e class "$class#propagatesCallbackFailureAndCancelsAfterFirstDenoisingStep" -e staticLcmCancelSmoke true -e storageKey $key $runner
```

코루틴 취소·삭제 테스트에는 `-PruntimeFixture=true`로 빌드한 `com.example.pocketmuseprototype.runtimefixture`만 사용한다. 검증된 ToonYou 변환 출력 세 개의 **별도 사본**을 격리 앱의 외부 전용 디렉터리에 넣는다. 테스트는 사본을 Library에 등록하고 삭제한다. 아래 설치와 `$pkg` 설정 후 위의 Tokenizer 복사 블록을 실행한다.

```powershell
.\gradlew.bat -PruntimeFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
$pkg = 'com.example.pocketmuseprototype.runtimefixture'
```

`$localModelCopy`는 세 출력 파일의 별도 로컬 사본이 있는 디렉터리다.

```powershell
$remote = "/sdcard/Android/data/$pkg/files/runtime-cancel-input"
adb shell mkdir -p "$remote"
foreach ($name in 'text_encoder.mnn','unet.mnn','vae_decoder.mnn') {
    adb push (Join-Path $localModelCopy $name) "$remote/$name"
}
$test = 'com.example.pocketmuseprototype.runtime.StaticLcmSmokeTest#coroutineCancellationKeepsFixtureFilesUntilNativeReturn'
adb shell am instrument -w -r -e class $test -e staticLcmDeleteSmoke true "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
```

## 실제 HQQ 모델 스모크

로컬에 이미 준비한 SD 1.5 safetensors와 호환되는 HQQ 템플릿을 사용한다. `adb`가 여러 기기를 감지하면 `ANDROID_SERIAL`로 테스트 기기를 선택한다. Gradle의 기본 계측 실행은 종료할 때 테스트 앱과 대상 앱을 제거할 수 있으므로, 모델 입력을 준비한 뒤에는 스모크를 ADB로 직접 실행한다.

```powershell
.\gradlew.bat -PconverterOnly=true :app:assembleDebug :app:assembleDebugAndroidTest
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

정적 LCM 변환 스모크는 같은 체크포인트와 호환 템플릿에 로컬의 공식 SD 1.5 LCM-LoRA 파일을 추가해 실행한다.

```powershell
adb push 'C:\path\to\lcm-lora-sdv1-5.safetensors' "$remote/lcm-lora.safetensors"
adb shell am instrument -w -r -e class com.example.pocketmuseprototype.conversion.ConverterSmokeTest -e converterLcmSmoke true com.example.pocketmuseprototype.test/androidx.test.runner.AndroidJUnitRunner
```

기존 앱 설치를 유지하며 두 스모크를 실행하려면 [격리 패키지 빌드와 실행 방법](docs/Converter.md#네이티브-빌드-성능-재현)을 따른다.

## 실제 백그라운드 변환 스모크

공용 LoRA가 없는 격리 패키지에 4,265,146,304바이트의 `v1-5-pruned-emaonly.safetensors`와 공식 SD 1.5 LCM LoRA를 준비한다. 테스트는 입력의 SHA-256을 확인한 뒤 체크포인트 공개 직후 상태를 만든다. LoRA 대기, 자동 WorkManager 예약, 변환 중 취소와 공용 LoRA 삭제, 재준비, 홈 화면에서 변환 완료를 검증한다. Library에 기록된 파일 크기와 SHA-256을 실제 출력과 대조한다. 기기 화면을 잠금 해제한 상태에서 실행한다.

```powershell
.\gradlew.bat -PconverterOnly=true -PconverterBenchmark=RelWithDebInfo :app:assembleDebug :app:assembleDebugAndroidTest
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

완료 후 격리 앱을 다시 열어 Library의 **내 모델**에서 **Conversion fixture**의 모델명·버전·용량과 삭제 버튼을 확인한 뒤 삭제한다. 다시 시험하기 전에 격리 앱의 공용 LoRA도 삭제한다. 원본, 출력과 LoRA 사본에 약 5.9 GB가 필요하다. [카드와 모달 상태](docs/Model-Downloads.md#library-카드와-모달)

### 검증 범위와 한계

Galaxy S20+에서 정적 LCM 모델의 OpenCL 추론, JNI 취소·오류 처리, 모델 파일 수명 관리, Room 마이그레이션 및 기존 기능 회귀 검증을 완료했다. 다만 다음 항목은 현재 검증 범위에 포함되지 않는다.

- **16 KiB 페이지 환경:** APK의 ELF·ZIP 정렬은 검증했지만 실제 16 KiB 페이지 기기에서 실행하지 않았다.
- **GPU 실행 중 취소:** UNet 스텝 사이의 취소와 자원 정리는 검증했지만, 개별 GPU 커널 실행 도중 취소 요청의 지연과 종료 동작은 측정하지 않았다.
- **프로세스 강제 종료:** 추론 중 프로세스가 종료되는 경우의 상태 복구 및 재실행은 검증하지 않았다.
- **메모리 안정성:** 실행 전후 PSS 표본은 기록했지만 추론 중 피크 메모리, 반복 생성 시 메모리 누수 및 장시간 안정성은 측정하지 않았다.
- **전체 사용자 흐름:** 실제 계정에서 체크포인트 재다운로드부터 정적 LCM 변환과 추론까지 연속 수행하는 E2E 검증은 실시하지 않았다.
- **OpenCL 캐시:** 변환 결과별로 디스크 캐시를 기본 사용한다. [Galaxy S20+ Cold/Warm 측정](docs/Runtime.md#galaxy-s20-opencl-캐시-검증)

위 항목은 현재 확인된 결함이 아니라 검증 범위의 한계다. 관련 기능 개발이나 실행 환경 확장 시 필요한 검증을 수행한다.
