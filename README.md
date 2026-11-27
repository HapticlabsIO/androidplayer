# HapticlabsPlayer

`HapticlabsPlayer` is a Kotlin class for Android that provides a high-level API for playing haptic and audio effects from Hapticlabs, including [`.hac`](https://docs.hapticlabs.io/mobile/hacfiles/), [`.hla`](https://docs.hapticlabs.io/mobile/hlafiles/), as well as [`.ogg`](https://docs.hapticlabs.io/mobile/oggfiles/) files. It automatically selects the best available haptic playback method based on the device's capabilities and manages resource preloading and unloading for low-latency playback.

## Library Installation

To use this library in your project, add it to your app level `build.gradle`'s `dependencies`:

```groovy
dependencies {
  implementation "io.hapticlabs:hapticlabsplayer:1.0.0"
}
```

Alternatively, register the library in your `libs.versions.toml`:

```toml
[versions]
hapticlabsplayer = "1.0.0"

[libraries]
hapticlabsplayer = { module = "io.hapticlabs:hapticlabsplayer", version.ref = "hapticlabsplayer" }
```

and add it in your app level `build.gradle.kts`' `dependencies`:

```kotlin
dependencies {
  implementation(libs.hapticlabsplayer)
}
```

Your app needs to compile against API level 36 or later, with a `compileSdk` of 36 or more.

After adding the library, you can easily import the `HapticlabsPlayer` class:

```kotlin
import io.hapticlabs.hapticlabsplayer.HapticlabsPlayer
```

## Public Member Functions

### `constructor(context: Context)`

Creates a new `HapticlabsPlayer` instance.

- **Parameters:**

  - `context`: Android `Context` used for resource access and system services.

- **Behavior:**
  - Initializes internal resources and detects device haptic capabilities.
  - Sets up audio and haptic routing for playback.

### `fun play(directoryOrHACPath: String, completionCallback: () -> Unit)`

Plays a haptic/audio effect from a `.hac` file or a legacy directory structure.

The legacy directory structure is as follows:

```
directoryPath
├── lvl1
│   └── main.hla
├── lvl2
│   └── main.hla
└── lvl3
    └── main.ogg
```

Support for this structure may be removed in future updates. Use `.hac` files instead.

- **Parameters:**

  - `directoryOrHACPath`: The path to the `.hac` file or legacy directory. Can be an absolute filesystem path or an asset path.
  - `completionCallback`: Callback invoked when playback is complete.

- **Behavior:**
  - Automatically detects the file type and device haptic support level.
  - Selects the best playback method (on/off, amplitude, OGG, or PWLE).
  - Supports legacy directory-based resources (deprecated).

---

### `fun playHLA(hlaPath: String, completionCallback: () -> Unit)`

Plays a haptic/audio effect from a `.hla` file.

- **Parameters:**

  - `hlaPath`: Path to the `.hla` file (absolute or asset path).
  - `completionCallback`: Callback invoked when playback is complete.

- **Behavior:**
  - Loads and parses the `.hla` file and associated resources.
  - Selects the best playback method based on device capabilities.
  - Plays the selected signal's audio file, if any, from the start. `.hla` version 2 is deprecated: its signals list several audio files at offsets, of which only the first plays, from the start. The same goes for legacy `.hla` files.

---

### `fun playHAC(hacPath: String, completionCallback: () -> Unit)`

Plays a haptic/audio effect from a `.hac` file.

- **Parameters:**

  - `hacPath`: Path to the `.hac` file (absolute or asset path).
  - `completionCallback`: Callback invoked when playback is complete.

- **Behavior:**
  - Loads and parses the `.hac` file and associated resources.
  - Selects the best playback method based on device capabilities.

---

### `fun playOGG(oggPath: String, completionCallback: () -> Unit)`

Plays an OGG file containing audio and/or encoded haptic feedback.

- **Parameters:**

  - `oggPath`: Path to the OGG file (absolute or asset path).
  - `completionCallback`: Callback invoked when playback is complete.

- **Behavior:**
  - Routes haptic playback to the device vibration actuator for best results.
  - Handles device speaker routing and fallback to MediaPlayer if necessary.
  - Plays along with OGGs that are playing already, mixing their haptics like their audio. Preloaded OGGs and audio play together up to 8 at a time, after which a new one stops the oldest.

---

### `fun playBuiltIn(name: String)`

Plays a built-in Android haptic effect.

- **Parameters:**

  - `name`: Name of the built-in effect. Must be one of:
    - `"Click"`
    - `"Double Click"`
    - `"Heavy Click"`
    - `"Tick"`

- **Behavior:**
  - Uses Android's predefined vibration effects if supported by the device.

---

### `fun preload(directoryOrHacPath: String)`

Preloads a `.hac` file or legacy directory for low-latency playback.

- **Parameters:**

  - `directoryOrHacPath`: Path to the `.hac` file or legacy directory (absolute or asset path).

- **Behavior:**
  - Parses and loads resources into memory.
  - Preloads its audio and OGG files if possible.
  - Call `unload()` to release resources.

---

### `fun preloadOGG(oggPath: String)`

Preloads an OGG file for low-latency playback.

- **Parameters:**

  - `oggPath`: Path to the OGG file (absolute or asset path).

- **Behavior:**
  - Loads the OGG file into memory if its uncompressed size is less than 1 MB.
  - Call `unloadOGG()` to release the resource.

---

### `fun unload(directoryOrHacPath: String)`

Unloads a `.hac` file or legacy directory previously loaded with `preload`.

- **Parameters:**

  - `directoryOrHacPath`: Path to the `.hac` file or legacy directory (absolute or asset path).

- **Behavior:**
  - Releases all associated resources from memory.

---

### `fun unloadOGG(oggPath: String)`

Unloads an OGG file previously loaded with `preloadOGG`.

- **Parameters:**

  - `oggPath`: Path to the OGG file (absolute or asset path).

- **Behavior:**
  - Releases the OGG resource from memory.

---

### `fun unloadAll()`

Unloads all resources previously loaded with `preload` or `preloadOGG`.

- **Behavior:**
  - Releases all loaded audio and OGGs and clears internal caches.

---

## Properties

### `val hapticsCapabilities: HapticCapabilities`

Describes the haptic capabilities of the current device, including support for on/off, amplitude, audio-coupled, and envelope effects, as well as actuator frequency response and quality factor. `HapticCapabilities` is in `io.hapticlabs.hapticlabsplayer.core`, see [Core library](#core-library).

---

## Usage Notes

- Always call `unload` or `unloadAll` to release resources when they are no longer needed.
- Use `preload` and `preloadOGG` to minimize playback latency for time-critical applications.
- The legacy directory-based approach is deprecated; prefer `.hac` files for new projects.
- Playback methods automatically select the best available haptic effect based on device capabilities.

## Core library

`hapticlabsplayer` builds on [`hapticlabsplayer-core`](https://github.com/HapticlabsIO/android-hapticlabsplayer-core), which it brings along: the device's capabilities and effects (`HapticDevice`) and audio and OGG playback (`HapticMediaPlayer`), which [`habplayer`](https://github.com/HapticlabsIO/android-habplayer) uses too. `HapticCapabilities`, `HapticPrimitive`, `LoadedEffect`, `LoadedAudio`, `UncompressedOGGFile` and `getVibrator` are in its package `io.hapticlabs.hapticlabsplayer.core`, having been in `io.hapticlabs.hapticlabsplayer` before version 1.0.0.

## Development

This repository is a standalone Gradle build. It uses React Native's Android Gradle plugin version, like [`habplayer`](https://github.com/HapticlabsIO/android-habplayer), as builds that include it, such as the example app or React Native apps building `habplayer` from source, need the same one.

```sh
./gradlew build
```

To build against a local `hapticlabsplayer-core` instead of the published one, pass the path of a [HapticlabsAndroidPackage](https://github.com/HapticlabsIO/android-example) checkout, whose submodules this repository and the core are, as in `./gradlew -PhapticlabsAndroidPackageDir=.. build`.

Generating OGGs from `.hab` files lives in [`habplayer`](https://github.com/HapticlabsIO/android-habplayer), which builds on this library.

### Releasing

Bump `version` in [`gradle.properties`](gradle.properties) (and the version in the installation instructions above) and merge to `main`. The [release workflow](.github/workflows/release.yml) then publishes the new version to Maven Central, tags it as `v<version>` and creates a GitHub release.

The workflows need these repository secrets:

| Secret | Description |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | Central Portal user token for the `io.hapticlabs` namespace |
| `GPG_PUBLIC_KEY`, `GPG_SECRET_KEY`, `GPG_PASSPHRASE` | Armored signing key pair and its passphrase |
