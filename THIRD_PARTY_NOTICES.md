# Third-Party Notices

Copyright (c) 2026 Yaseen91479 - GitHub: Yaseen91479 - yaseenwaleeddis99@gmail.com

## Shizuku API / Provider

This EchoRoute build declares these direct dependencies:

- `dev.rikka.shizuku:api:13.1.5`
- `dev.rikka.shizuku:provider:13.1.5`

These pull in further modules from the same Shizuku-API project as transitive dependencies (for example `dev.rikka.shizuku:aidl` and `dev.rikka.shizuku:shared`). Maven Central metadata lists the same MIT License for api, provider and aidl.

Project: RikkaApps/Shizuku-API
License: MIT License
Copyright holder/developer attribution: Rikka

Source and license:
https://github.com/RikkaApps/Shizuku-API

The Shizuku-API repository identifies its API and provider distribution under the MIT License. EchoRoute does not claim ownership of Shizuku code and does not relicense it as proprietary EchoRoute code.

### MIT License Notice

Permission is hereby granted, free of charge, to any person obtaining a copy of the software and associated documentation files, to deal in the software without restriction, subject to the conditions of the applicable MIT License, including preservation of the copyright and permission notice and the license terms.

The MIT License text applicable to this dependency is reproduced below for license notice purposes.

MIT License

Copyright (c) Rikka

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Android platform

EchoRoute uses Android framework APIs and the Android SDK as platform/build dependencies. This archive does not redistribute Android platform source code. Android and AOSP materials remain subject to their applicable licenses and notices.

## Android Open Source Project / hidden APIs

EchoRoute talks to Android system services (audio policy, audio flinger, activity manager) through the Android framework, reflection and shell commands run through Shizuku. No AOSP source code is copied into this project. Android is a trademark of Google LLC.

## Shizuku name

"Shizuku" and "Sui" are names of projects by RikkaApps. EchoRoute is not made, endorsed or sponsored by RikkaApps. The name is used only to say which service EchoRoute needs.

## Build-time and transitive libraries

Android Gradle Plugin and any AndroidX libraries that Gradle pulls in transitively are normally licensed under the Apache License 2.0. To get the exact, current list for your build, run:

    ./gradlew :app:dependencies --configuration releaseRuntimeClasspath

and add any extra library (name, version, license, copyright holder) to this file before you distribute the app.
