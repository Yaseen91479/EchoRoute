# EchoRoute

Copyright (c) 2026 Yaseen91479
GitHub: Yaseen91479
Contact: yaseenwaleeddis99@gmail.com
All rights reserved.

## What EchoRoute does

EchoRoute is an Android microphone-effects controller. It does not open or own the physical microphone itself. With Shizuku available, it uses Android's audio-policy layer to install source-default microphone effects so that matching microphone recording sessions can receive the selected effects.

Current effects in this clean build:

- Acoustic Echo Cancellation (AEC)
- Noise Suppression (NS)
- Automatic Gain Control V2 (AGC2)

The app supports Automatic and Manual control modes. It can inspect active microphone recording configurations, track the relevant microphone session, and perform the project's existing cleanup/force-stop lifecycle before removing its source-default registrations. The controller and Shizuku-side user service keep the effect state synchronized and record diagnostic status locally. Raw microphone PCM is not recorded by EchoRoute.

## Runtime requirements

- Android 10 / API 29 or newer.
- Shizuku is required for the privileged audio-policy operations used by this build.
- Root is not required when Shizuku is running with the required privilege.
- EchoRoute does not request `RECORD_AUDIO` and does not open a microphone stream itself.

## Permissions and access

Declared permissions are limited to the Shizuku API permission, foreground-service permissions, and notification permission required by the current architecture. The app does not declare `INTERNET`.

## Privacy audit

This archive was checked for hard-coded contact details, phone numbers, email addresses, API keys, access tokens, tracking identifiers, and personal links. No such personal data was found in the project source. The only intentional attribution/contact data added to this archive is:

- GitHub username: `Yaseen91479`
- Contact email: `yaseenwaleeddis99@gmail.com`

The package identifier `com.echoroute.aec` is an application namespace, not a phone number or contact credential.

The local diagnostic log is stored inside the app's private files directory. The code does not log raw microphone PCM.

## Licensing

EchoRoute's original source code, documentation, project files, branding text, and original assets in this archive are proprietary and are covered by the included `LICENSE` file. All rights are reserved except for permissions explicitly granted by the copyright holder in writing.

EchoRoute also uses the Shizuku API and provider, version 13.1.5. Those third-party components remain under their own MIT License and are not relicensed as EchoRoute. See `THIRD_PARTY_NOTICES.md` for the attribution and license reference.

Android framework APIs and the Android SDK are external platform dependencies; this source archive does not redistribute Android's platform source code.

## Contact

For project-related contact only:
`yaseenwaleeddis99@gmail.com`

## Rights files

`LICENSE`, `COPYRIGHT`, `THIRD_PARTY_NOTICES.md`, `ASSET_RIGHTS.md`, `RIGHTS_GUIDE.md` and `PRIVACY_AUDIT.md` together describe who owns what and under which terms. Read `RIGHTS_GUIDE.md` first if you are unsure.
