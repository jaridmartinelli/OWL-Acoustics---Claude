# OWL Acoustics — fresh Android build (development)

Android room-acoustics app built from the supplied brief in [`brief/`](brief/). Records:
[`docs/OWL_MASTER_SPEC.md`](docs/OWL_MASTER_SPEC.md) · [`docs/DECISIONS.md`](docs/DECISIONS.md) ·
[`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md) · [`PROGRESS.md`](PROGRESS.md) (status dashboard).

**This is a labeled experimental development build**, not a production release. Automatic video
reconstruction, live server analysis, expert-service purchases and the final logo are not available —
see PROGRESS.md for the exact list and why.

## Modules

| Module | What it holds |
|---|---|
| `domain/` | Pure Kotlin/JVM: exact units, canonical room model, validated edit commands, opening gestures, linear history, transactional file store (schema v1), ceiling profiles, 3D mesh, reflections/SBIR/modes, placement optimiser, recommendations, catalog, purchase state machine, capture/import contracts. |
| `app/` | Jetpack Compose UI, CameraX silent capture, image/PDF import, Robolectric-rendered UI tests. |

## Build environment (pinned)

- JDK 21 on the host (bytecode target 17)
- Android SDK: `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`
- Gradle 8.14.3 via the committed wrapper; AGP 8.13.2; Kotlin 2.2.21; Compose BOM 2025.10.01; CameraX 1.5.1
- Repositories: Google, a Google-hosted Maven Central mirror, Maven Central (see DECISIONS E-001)

Create `local.properties` (not committed) with `sdk.dir=/path/to/android-sdk`, then:

```sh
./gradlew :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. No secrets or machine-specific paths are
committed. Byte-identical rebuilds are not configured or claimed.

`:app:connectedDebugAndroidTest` is not used: there are no instrumented cases yet and no emulator/device was
available in the build container (no KVM).

## Identity and signing

- Application ID `com.owlacoustics.roomplanner.dev` (D-12, reversible development ID), label "OWL Acoustics (Dev)",
  versionCode 1, versionName 0.1.0-dev, minSdk 26, targetSdk 36.
- Debug builds are signed with the build machine's auto-generated Android debug key (development only).
  The certificate digest of a given APK is recorded in PROGRESS.md for that artifact.
- Release signing, public namespace and distribution are **not configured** (B-09); release builds are unsigned.

## Data on the device

Rooms, history and source media are stored only in app-private storage. Automatic cloud backup and
device-to-device transfer are disabled and excluded (D-17). Uninstalling the app or losing the phone removes them.
