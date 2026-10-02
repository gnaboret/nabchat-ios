# iOS release path

The Windows side of the iOS port is automated. Every push compiles the app for an iPhone/iPad simulator and for physical-device arm64 on a GitHub-hosted Mac. The `Upload iOS build to TestFlight` workflow is ready for signing once the Apple account setup is complete.

## Apple setup needed once

1. Enroll in the Apple Developer Program.
2. In App Store Connect, create **nabchat** with bundle ID `com.nabchat.app`.
3. Create the non-consumable in-app purchase `nabchat_plus`.
4. Create an App Store distribution certificate and an App Store provisioning profile for `com.nabchat.app`.
5. Create an App Store Connect API key with App Manager access.
6. Add the following GitHub Actions secrets to the `nabchat-ios` repository:

   - `APPLE_CERTIFICATE_P12_BASE64`
   - `APPLE_CERTIFICATE_PASSWORD`
   - `APPLE_PROVISIONING_PROFILE_BASE64`
   - `APPLE_PROVISIONING_PROFILE_NAME`
   - `APPLE_KEYCHAIN_PASSWORD`
   - `APPLE_TEAM_ID`
   - `APP_STORE_CONNECT_API_KEY_BASE64`
   - `APP_STORE_CONNECT_API_KEY_ID`
   - `APP_STORE_CONNECT_ISSUER_ID`

The two files are stored as base64 text so GitHub can reconstruct them privately on its temporary Mac runner. None of the signing material belongs in Git.

## Uploading from Windows

Open GitHub → **Actions** → **Upload iOS build to TestFlight** → **Run workflow**. Enter a build number higher than the previous upload. The workflow builds, signs, and uploads the app; the iPad then installs it through Apple's TestFlight app.

The first TestFlight build still requires completing Apple's app privacy, age rating, pricing, screenshots, export compliance, and review information in App Store Connect.
