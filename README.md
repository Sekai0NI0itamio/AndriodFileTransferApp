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
- macOS DMG, packaged unsigned for personal use

## Release Identity

The packaged apps now carry explicit publisher metadata.

- Author: Itamio Pupmann
- Organization: AsdUnionTech
- Support email: itamioa743@gmail.com
- Android application ID: asd.itamio.localbridge
- macOS bundle ID: asd.itamio.localbridge

## Required Secrets

Add the Android repository secrets in GitHub Actions if you want signed Android release artifacts.

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## How To Generate Android Keystore

These Android secrets are created from a local keystore file first, then copied into GitHub as base64 or plain text values.

### Android Keystore

Create the keystore file first. The file must exist before you run `base64`.

```bash
keytool -genkeypair -v \
	-keystore localbridge-release.jks \
	-storetype JKS \
	-alias localbridge \
	-keyalg RSA \
	-keysize 2048 \
	-validity 10000
```

Then encode it for GitHub Secrets.

```bash
base64 -i localbridge-release.jks | pbcopy
```

Add the copied text to `ANDROID_KEYSTORE_BASE64`. Use the same password you entered for the keystore as both `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD` unless you intentionally chose separate ones. Set `ANDROID_KEY_ALIAS` to the alias you used, for example `localbridge`.

## macOS Build

The macOS app now packages without a signing certificate or notarization credentials.

If you run the GitHub Actions workflow or the Gradle task locally, it builds an unsigned DMG for personal use. No Apple Developer certificate, app-specific password, or team ID is required for that path.

### Add Android Secrets In GitHub

In your repository on GitHub, open `Settings` -> `Secrets and variables` -> `Actions` -> `New repository secret`, then create the Android secret names from the list above if you want signed Android release artifacts.

## Notes

- Android uses cleartext HTTP for same-LAN transfer, so the manifest enables `usesCleartextTraffic`.
- The current implementation is designed around both devices running the app while transferring.
- macOS release packaging is intentionally unsigned to keep personal builds simple.
