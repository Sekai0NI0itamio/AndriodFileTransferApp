# LocalBridge

LocalBridge is a same-network file transfer app for Android phones and macOS computers. The project is set up as a single Compose Multiplatform codebase so the Android app and the macOS app share the same transfer engine, discovery layer, and most of the UI.

## Product Direction

- Minimal black-and-white interface.
- Same-LAN device discovery.
- Image and general file transfer in both directions.
- Progress, size, remaining bytes, and transfer state visibility.
- Pause and resume support backed by partial-file persistence and file fingerprints.
- macOS packaging aimed at Apple Silicon through GitHub Actions.

## GitHub Build Workflow

This repository is configured to build on GitHub Actions instead of locally.

- Android artifacts are built on `ubuntu-latest`.
- macOS artifacts are built on `macos-15`, which GitHub currently documents as an arm64 Apple Silicon runner.
- The combined workflow file lives at `.github/workflows/build.yml`.

Artifacts uploaded by the workflow:

- Android release APK/AAB
- macOS DMG

## Notes

- Android uses cleartext HTTP for same-LAN transfer, so the manifest enables `usesCleartextTraffic`.
- The current implementation is designed around both devices running the app while transferring.
- Signing and notarization are not configured yet; GitHub Actions currently produces unsigned build artifacts suitable for internal testing.
