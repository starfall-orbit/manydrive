# ManyDrive

ManyDrive is a native Android file-manager starter built with Kotlin and Jetpack Compose.

## Requirements

- Android Studio Ladybug or newer
- JDK 17
- Android SDK Platform 35

## Run

Open this repository directly in Android Studio, then run the `app` configuration; or use:

```bash
./gradlew :app:assembleDebug
```

The project has no Flutter or Dart runtime. It uses a Kotlin/Jetpack Compose Material 3 interface, Google account picker, Google Identity authorization, and the official Google Drive Java SDK.

## Release builds on Codemagic

1. Open **Team settings → codemagic.yaml settings → Code signing identities → Android keystores**.
2. Upload your release keystore and enter its keystore password, key alias, and key password.
3. Set its **Reference name** to `ManyDrive`, matching `environment.android_signing` in `codemagic.yaml`. If you already uploaded it with another reference name, update the YAML to match.
4. Create a Codemagic environment variable group named `github_release`. Add `GITHUB_TOKEN` as a **Secret**: a GitHub fine-grained personal access token with access to `starfall-org/manydrive` and **Contents: Read and write** (approve it for the organization if required). The release scripts also accept the legacy `GH_TOKEN` name.
5. Run the `android-release` workflow. After the signed APKs and AAB build successfully, it publishes them to GitHub Release `v<versionName>` (for example, `v3.0.0`).

The publishing script reads the version from the APK output metadata and targets the exact build commit. New releases stay drafts until all APKs and the AAB upload successfully. A rerun for the same tag and commit replaces matching assets; reusing a version for a different commit fails instead of publishing mismatched binaries. If building a tag, it must match `v<versionName>`. Debug builds do not publish releases or require a GitHub release token.

See the [GitHub CLI release documentation](https://cli.github.com/manual/gh_release_create) and [Codemagic environment variables](https://docs.codemagic.io/yaml-basic-configuration/configuring-environment-variables/).

Codemagic supplies `CM_KEYSTORE_PATH`, `CM_KEYSTORE_PASSWORD`, `CM_KEY_ALIAS`, and `CM_KEY_PASSWORD` to Gradle. Release signing requires these variables; it no longer uses the debug keystore. For local release builds, provide the same environment variables. Debug builds do not require them.

See [Codemagic's Android signing guide](https://docs.codemagic.io/yaml-code-signing/signing-android/).

## Configure Google sign-in and Drive

1. In Google Cloud Console, enable **Google Drive API** and **Photos Library API** for the project.
2. Configure the OAuth consent screen and add test users if the app is in testing.
3. Create an OAuth 2.0 **Android** client for package `com.starfall.gsadrive` and the SHA-1 of the signing certificate. Configure each debug/release certificate used to install the app (including the Play app-signing certificate when applicable).

The app opens Google's account picker to select an account already on the device, then requests access directly with `AuthorizationClient`, bound to that account. It does not require a Web OAuth client ID or Firebase Authentication. Only the selected account email is persisted; access tokens stay in memory. Signing out clears the local session without removing the Google account from the device or revoking consent.

The app requests the full Google Drive scope to list both My Drive and shared files. This is a restricted Google scope; production release may require OAuth verification/security assessment.

## Accounts and storage providers

The profile button opens one account list for **Google**, **S3**, and **Service Account**. **Thêm tài khoản** offers these three connection types. Selecting any account replaces the active storage provider on the Files screen; there is no separate S3 tab. The selected account is restored on restart. Files and Shared remain visible. Google enables both; S3 enables only Files; Service Account enables Files and Shared. Unsupported tabs are disabled. For Google and Service Account, Files starts at the account's own Drive root and only lists items owned by that account there; incoming shared folders and files appear in Shared. A service account with only incoming shares therefore has an empty Files tab. Opening a folder in Shared lists its children by parent ID, including children accessible through inherited permissions.

- **Google:** choose a Google account on the device and grant Drive access. Photos upload permission is requested from the “Tải lên Google Photos” file menu.
- **S3:** enter a name, HTTPS endpoint, bucket, region and access keys in a dialog. The app verifies bucket access before saving. Multiple connections are supported.
- **Service Account:** choose a Google service-account JSON key with Android's file picker. The app validates its type, email and RSA private key, obtains an OAuth token, and verifies Drive access before saving. Reimporting the same service-account email replaces its saved key. Enable Drive API in that account's project and share files with its `client_email`, or grant it access to a shared drive. Tokens refresh when needed during browsing. Domain-wide impersonation is not configured.

S3 and Service Account currently display file listings. Service accounts have no personal Drive storage quota, so the app does not offer uploads into their root. See [Google's service account OAuth guide](https://developers.google.com/identity/protocols/oauth2/service-account) and [shared drive guidance](https://developers.google.com/workspace/drive/api/guides/about-shareddrives).

Access keys and imported private keys are encrypted with AES-GCM using device-bound Android Keystore keys, in the app's no-backup directory. The JSON source is read once; its path is not retained. Existing S3 connections remain compatible with the encrypted storage format. Removing an account deletes its saved connection without deleting remote files. Signing out clears the active session while keeping saved accounts available in the account list. Reinstalling or moving to another device requires importing credentials again.

## API clients

Drive file operations use `google-api-services-drive`, with Google Auth Library for service-account JWT signing and token exchange. S3 uses the official AWS SDK for Kotlin. Access tokens are supplied separately for each Google client; upload/download content is streamed by the SDK.

Google Photos uses the [Library REST API](https://developers.google.com/photos/library/guides/upload-media) through the official Google HTTP Client with `photoslibrary.appendonly`. There is no separate Photos browser or Picker. The “Tải lên Google Photos” menu is available for images, videos, and folders from Google Drive, S3, and Service Accounts. Google Drive uploads to the active Google account; other sources prompt for a connected Google destination. A folder creates an album with the same name and uploads images/videos recursively, skipping other files. Individual media files upload directly to the library. Transfers use temporary files, remove them after each upload, and report success/failure counts.
