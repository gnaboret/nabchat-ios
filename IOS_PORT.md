# NabChat iOS port

NabChat is currently a native Android application built with Kotlin and Jetpack Compose. The iOS port will use Kotlin Multiplatform and Compose Multiplatform so the existing domain models, chat parsing, filtering, analytics, and substantial portions of the Compose UI can be shared.

## Guardrails

- Keep the existing `app` module and Android release build working throughout the port.
- Never commit Android signing keys, Apple certificates, provisioning profiles, API secrets, or local SDK paths.
- Build iOS on a GitHub-hosted macOS runner; test distributable builds through TestFlight.
- Keep platform services behind interfaces so Android continues to use Room, DataStore, Google Play Billing, and AdMob while iOS uses Apple-compatible implementations.

## Phases

1. Preserve the current Android project as the source-control baseline.
2. Introduce a Kotlin Multiplatform shared module without changing Android behavior.
3. Move pure models, content policy, emote parsing, emoji signals, and provider-independent analytics into shared code with tests.
4. Make HTTP chat providers portable using Ktor or platform-neutral transports.
5. Extract reusable Compose UI from `MainActivity.kt` into shared presentation code.
6. Add an iOS application shell and iOS implementations for persistence, settings, links, file export, and screen-awake behavior.
7. Integrate Apple in-app purchase/restore behavior and iOS advertising/consent.
8. Configure signed GitHub Actions builds and distribute to the test iPad through TestFlight.

## Apple prerequisites

- Apple Developer Program membership is required for TestFlight and App Store distribution.
- App Store Connect app record, bundle identifier, distribution certificate, and provisioning profile.
- iOS AdMob app/ad-unit identifiers and an App Store non-consumable product corresponding to NabChat+.
