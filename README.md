# EchoRoute™

Android app that turns on microphone effects (echo cancellation, noise
suppression, automatic gain control) for apps that don't enable them
themselves. It works through Shizuku and Android's audio policy, so EchoRoute
never opens the microphone itself.

## Effects

- Acoustic Echo Cancellation (AEC)
- Noise Suppression (NS)
- Automatic Gain Control V2 (AGC2)

## Requirements

- Android 10 (API 29) or newer
- [Shizuku](https://github.com/RikkaApps/Shizuku) installed and running
- No root needed

## Usage

1. Start Shizuku and allow EchoRoute when it asks for permission.
2. Open EchoRoute and pick a mode:
   - **Automatic**: applies the effects to whichever app is recording.
   - **Manual**: applies them only to the apps you choose.
3. Press START. Press STOP to remove the effects again.

The Activity tab lets you choose effects per app, and the Log tab shows what
EchoRoute is doing.

## Privacy

EchoRoute has no `INTERNET` permission and no `RECORD_AUDIO` permission. It
doesn't record audio. The log is a small text file kept in the app's private
storage. Details are in [PRIVACY.md](PRIVACY.md).

## Building

Open the project in Android Studio or AndroidIDE, sync Gradle and run
`:app:assembleDebug`. The APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

## Legal

- [LICENSE](LICENSE): all rights reserved
- [Terms of Use](TERMS_OF_USE.md)
- [Privacy Policy](PRIVACY.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md): Shizuku is used under the MIT license

EchoRoute™ is the name used by the author for this app. It is not a registered trademark.
