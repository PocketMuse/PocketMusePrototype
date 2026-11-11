# Civitai 계정 연결

Market → Image에서 계정을 연결한다. PocketMusePrototype은 공개 클라이언트 `33f359b1-7721-4e44-9b3e-530bb0734a89`로 등록되어 있다. 요청 권한은 `ModelsRead`이며 `UserRead`는 Civitai가 자동 부여한다. 인증·토큰·사용자 정보·폐기 요청은 `https://auth.civitai.com/api/auth/oauth/`의 `authorize`, `token`, `userinfo`, `revoke`를 사용한다. [Civitai OAuth 개발 문서](https://github.com/civitai/civitai/blob/main/docs/auth/oauth-developer-docs.md)

## Checkpoints

- AppAuth 0.11.1이 외부 브라우저, Authorization Code와 S256 PKCE, state 검사, 코드 교환을 담당한다. `https://pocketmuse.github.io/oauth/callback/`은 앱의 `RedirectUriReceiverActivity` 한 곳에서 받는다. 이 Activity는 정확한 URI를 확인하고 `state`와 `code` 또는 `error`를 AppAuth에 전달한다. `MainActivity`는 Activity Result를 받고 프로세스의 `AuthManager`가 현재 시도의 state와 결과를 대조한 뒤 한 번만 소비한다. 브라우저 복귀 후에도 토큰 교환과 UserInfo 조회가 끝나야 연결 상태가 된다. [AppAuth](https://github.com/openid/AppAuth-Android/tree/0.11.1), [Android App Links](https://developer.android.com/training/app-links/verify-applinks)
- AppAuth 0.11.1의 콜백 파서는 이 흐름에서 사용하지 않는 매개변수도 읽고, state 불일치 시 두 값을 로깅한다. 수신 Activity에서 전달 필드를 제한하고 앱 시작 시 AppAuth의 로거를 비활성화한다. AppAuth 갱신 시 이 통합 지점과 콜백 테스트를 다시 확인한다. [AppAuth 응답 처리 소스](https://github.com/openid/AppAuth-Android/blob/0.11.1/library/java/net/openid/appauth/AuthorizationManagementActivity.java)
- Android Keystore의 AES-256-GCM 키로 access token, refresh token, 만료 시간을 암호화한다. 두 토큰은 `noBackupFilesDir`에서 한 파일로 원자적으로 교체된다. 브라우저 요청은 Activity 복원에 필요한 동안 Android의 Activity 상태에 남고, 세션 파일에는 토큰 쌍과 만료 시간만 들어간다. 사용자 ID와 사용자명은 메모리에 보관하며 프로세스 재시작 시 UserInfo에서 다시 조회한다. [Android 암호화](https://developer.android.com/privacy-and-security/cryptography), [Android 백업 제외 경로](https://developer.android.com/identity/data/autobackup#Files)
- 저장 세션 복원, 앱 복귀, 유효 토큰 요청에서 만료까지 60초 이하이면 갱신한다. 앱 프로세스의 Mutex가 갱신과 해제를 직렬화하고 갱신된 쌍의 저장을 완료한 뒤 토큰을 제공한다. 일시적인 네트워크·저장·프로필 오류는 재시도할 수 있다. `invalid_grant`, 갱신 후에도 계속되는 UserInfo 401, 복구할 수 없는 암호화 데이터는 다시 연결이 필요하다. 서버가 갱신을 완료한 뒤 응답을 잃거나 저장 전에 프로세스가 종료되면 이전 쌍은 이미 폐기됐을 수 있다. [Civitai 갱신 계약](https://github.com/civitai/civitai/blob/main/docs/auth/oauth-developer-docs.md#refreshing-tokens)
- 연결 해제는 `client_id`와 refresh token으로 폐기를 요청한 뒤 로컬 세션을 삭제한다. 네트워크 오류 후에도 로컬 삭제가 완료되며 화면에 폐기를 확인할 수 없었다고 안내한다. Civitai의 HTTP 200은 토큰의 기존 상태를 증명하지 않는다. [Civitai 폐기 계약](https://github.com/civitai/civitai/blob/main/docs/auth/oauth-developer-docs.md#revoking-tokens)
- 지원 브라우저의 Auth Tab은 HTTPS 콜백을 Activity Result로 전달한다. 앱은 기존 콜백 주소와 `state` 검사를 거쳐 AppAuth가 생성한 S256 verifier로 코드를 교환한다. Activity 재생성에 필요한 동안 verifier는 Android의 Activity 상태에 보존된다. 지원하지 않는 브라우저에서는 AppAuth Custom Tab과 App Link 수신 Activity를 사용한다. Galaxy S20+의 Chrome 156에서 Custom Tab은 웹 콜백 페이지에 머물렀고, Auth Tab 적용 후 같은 기기에서 로그인과 세션 저장을 확인했다. [Chrome Auth Tab](https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab)
- 공개 카탈로그는 인증 상태와 독립적으로 조회·페이지 이동한다. 향후 다운로드 계층은 `AuthManager.validAccessToken()`을 호출해 사용할 Bearer 값을 얻는다. 반환값은 일시적으로만 사용하고 로그와 파일에 남기지 않는다. `null`은 현재 사용 가능한 세션이 없음을 의미하며 연결·재시도 안내는 `AuthManager.state`를 따른다. 호출자가 취소되어도 시작된 갱신은 앱 프로세스에서 저장까지 완료한다. 현재 모델 카드의 다운로드 동작은 비활성화되어 있다.

## 검증

- `:app:assembleDebug`, `:app:testDebugUnitTest` 28개, `:app:lintDebug`를 통과했다. `:app:connectedDebugAndroidTest` 13개는 Galaxy S20+ (SM-G986N, Android 13)에서 통과했다.
- 같은 기기에서 사용자가 실제 Civitai 로그인과 동의를 완료했다. 프로세스를 종료하고 다시 실행한 뒤 Market에서 사용자명과 공개 모델 카드를 확인했다. 계정 메뉴로 연결을 해제한 뒤 암호화 세션 파일 삭제와 미연결 화면을 확인했다. 설치한 APK는 `pocketmuse.github.io: verified`이며 콜백 담당 Activity는 하나다.
- 토큰 만료와 회전, 동시에 들어온 갱신 요청, 일시적인 오류는 합성 응답을 사용하는 호스트 테스트에서 검증했다. 실제 Civitai 서버에서 토큰 갱신은 수행하지 않았다.
