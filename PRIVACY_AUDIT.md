# EchoRoute Privacy / Personal-Data Audit

Audit target: EchoRoute-Clean-AutoManual-ForceStop-compile-fix.zip
Audit date: 2026-10-03

## Findings

No hard-coded phone number, personal email, API key, access token, authentication secret, social-media account URL, or tracking identifier was found in the source archive.

The package namespace is `com.echoroute.aec`. This is an Android application identifier and is not a contact credential.

The archive does not declare the `INTERNET` permission.

The archive does not declare `RECORD_AUDIO`. EchoRoute does not open the physical microphone itself; it controls Android source-default microphone effects through the Shizuku-assisted audio-policy path.

The local diagnostic logger writes `echoroute_app.log` to the application's private files directory. The source explicitly states that raw microphone PCM is not logged.
Runtime diagnostic entries can include microphone-app package names, UIDs, session IDs, and effect status because those values are needed for control/diagnostics. They are written to the app-private log file and are not sent over the network by this build.

## Intentional attribution data

The only personal attribution/contact information intentionally included in this prepared archive is:

- GitHub username: `Yaseen91479`
- Contact email: `yaseenwaleeddis99@gmail.com`

No phone number is included.

## Cleanup performed

- Removed stale Xposed/IEchoProcessor ProGuard rules.
- Removed the obsolete `app/proguard-rules.pro` file because release minification is disabled and those rules were unrelated to this clean build.
- Removed the stale `EchoRouteXposedStage3` root project name and replaced it with `EchoRoute`.
- Removed empty resource metadata directories.
- Added explicit ownership and third-party licensing files.

## Current version

- New permission: `RECEIVE_BOOT_COMPLETED`, used only to restore the saved running state after a reboot. No data leaves the phone.
- New saved state (app-private preferences): last state name, time, and a "cleanup pending" flag. No personal data.
- Notification "FORCE CLOSE" force-stops the foreground app and/or the app holding the microphone through Shizuku. It does not read or copy any app data.
- Still no `INTERNET` permission and no `RECORD_AUDIO` permission.
