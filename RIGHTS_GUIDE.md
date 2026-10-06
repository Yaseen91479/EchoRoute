# EchoRoute - Rights Guide

Copyright (c) 2026 Yaseen91479 - GitHub: Yaseen91479 - yaseenwaleeddis99@gmail.com

This is a plain explanation, not legal advice. For anything important (selling the app, publishing it in a store, a dispute), ask a lawyer in your country.

## Kinds of rights and how EchoRoute covers each

1. **Copyright.** Automatic; belongs to the author of the code, text and artwork and needs no registration in most countries. Every source file and the main documents carry "Copyright (c) 2026 Yaseen91479" with the GitHub username and contact email.
2. **License.** Says what other people may do with the work. EchoRoute uses a proprietary, all-rights-reserved license (`LICENSE`): nobody may copy, modify, sell, redistribute or reverse engineer it without written permission. Open-source licenses (MIT, Apache-2.0, GPL) do the opposite and allow sharing under conditions.
3. **Third-party licenses.** Code written by others keeps its own license. EchoRoute uses Shizuku API/provider under the MIT License; their copyright and license text are kept in `THIRD_PARTY_NOTICES.md`.
4. **Name and logo (trademark).** The name "EchoRoute", the icon and the logo are reserved in `LICENSE` section 6. This is an unregistered claim. Registering the name as a trademark with the trademark office of your country makes it much stronger and is the usual step before selling the app.
5. **Artwork.** The icon and images are covered in `ASSET_RIGHTS.md`, and the PNG files carry embedded author and copyright fields.
6. **Patents.** Protect an invention's method. None is claimed and none is granted by `LICENSE`.
7. **Privacy.** The app has no INTERNET permission and keeps its logs in its private folder (`PRIVACY_AUDIT.md`). A store listing may still require a privacy policy.
8. **Platform rules.** Google Play and other stores have their own policies, for example about apps that rely on hidden APIs or Shizuku.
9. **Contracts.** Terms you attach to a download (no resale, no redistribution) work as a contract and do not depend on copyright alone.

## What to keep doing

- Keep the "Copyright (c) 2026 Yaseen91479" header in every source file.
- Ship `LICENSE`, `COPYRIGHT`, `THIRD_PARTY_NOTICES.md` and `ASSET_RIGHTS.md` with any copy of the source.
- Before publishing a build, run the dependency command in `THIRD_PARTY_NOTICES.md` and add any new library.
- Keep the full git history and your original backups; they show who wrote what and when.
- Keep the app signing key private and backed up.
- If you want others to reuse the code, change `LICENSE` to an open-source license on purpose; do not mix.

## On GitHub

- A private repository is the safest. In a public repository, GitHub's terms let other users view and fork it on the platform.
- Keep `LICENSE` in the repository root.
- To remove a copy that someone published without permission, send GitHub a DMCA takedown notice; it is made under penalty of perjury, so it must only claim material you own.
