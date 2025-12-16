# HapticlabsPlayer

`HapticlabsPlayer` is a Kotlin class for Android that provides a high-level API for playing haptic and audio effects from Hapticlabs, including [`.hac`](https://docs.hapticlabs.io/mobile/hacfiles/), [`.hla`](https://docs.hapticlabs.io/mobile/hlafiles/), as well as [`.ogg`](https://docs.hapticlabs.io/mobile/oggfiles/) files. It automatically selects the best available haptic playback method based on the device's capabilities and manages resource preloading and unloading for low-latency playback.

## Library Installation

To use this library in your project, add it to your app level `build.gradle`'s `dependencies`:

```groovy
dependencies {
  implementation "io.hapticlabs:hapticlabsplayer:0.6.3"
}
```

Alternatively, register the library in your `libs.versions.toml`:

```toml
[versions]
hapticlabsplayer = "0.6.3"

[libraries]
hapticlabsplayer = { module = "io.hapticlabs:hapticlabsplayer", version.ref = "hapticlabsplayer" }
```

and add it in your app level `build.gradle.kts`' `dependencies`:

```kotlin
dependencies {
  implementation(libs.hapticlabsplayer)
}
```

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
  - Preloads OGG files if possible.
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
  - Releases all loaded OGGs and clears internal caches.

---

## Properties

### `val hapticsCapabilities: HapticCapabilities`

Describes the haptic capabilities of the current device, including support for on/off, amplitude, audio-coupled, and envelope effects, as well as actuator frequency response and quality factor.

---

## Usage Notes

- Always call `unload` or `unloadAll` to release resources when they are no longer needed.
- Use `preload` and `preloadOGG` to minimize playback latency for time-critical applications.
- The legacy directory-based approach is deprecated; prefer `.hac` files for new projects.
- Playback methods automatically select the best available haptic effect based on device capabilities.
