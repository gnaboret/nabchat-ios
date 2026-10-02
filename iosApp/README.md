# nabchat for iOS

The SwiftUI app links the Kotlin Multiplatform `NabchatShared` framework and is generated from `project.yml` during GitHub-hosted macOS builds. The Android source remains preserved in the same repository.

Implemented iOS features include River, Rooms, Rug, persistent channel management, Kick chat, Twitch device authorization and EventSub chat, experimental public YouTube live chat, local history, saved chatters, analytics, PULSE, emote bursts, CSV export, onboarding, appearance settings, StoreKit 2 nabchat+, a six-enabled-channel free limit, privacy metadata, and iPhone/iPad layouts.

Every push compiles both an unsigned simulator app and a physical-device arm64 target on a GitHub-hosted Mac. The simulator `.app` is retained as a workflow artifact. See `RELEASE.md` for the one-time Apple credentials and GitHub secrets required to upload signed builds to TestFlight entirely from Windows.
