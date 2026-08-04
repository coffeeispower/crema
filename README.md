# crema

> The extensible JVM-based Wayland compositor.

```
                                                                              
      _____        _____        ______        ______  _______         _____   
  ___|\    \   ___|\    \   ___|\     \      |      \/       \    ___|\    \  
 /    /\    \ |    |\    \ |     \     \    /          /\     \  /    /\    \ 
|    |  |    ||    | |    ||     ,_____/|  /     /\   / /\     ||    |  |    |
|    |  |____||    |/____/ |     \--'\_|/ /     /\ \_/ / /    /||    |__|    |
|    |   ____ |    |\    \ |     /___/|  |     |  \|_|/ /    / ||    .--.    |
|    |  |    ||    | |    ||     \____|\ |     |       |    |  ||    |  |    |
|\ ___\/    /||____| |____||____ '     /||\____\       |____|  /|____|  |____|
| |   /____/ ||    | |    ||    /_____/ || |    |      |    | / |    |  |    |
 \|___|    | /|____| |____||____|     | / \|____|      |____|/  |____|  |____|
   \( |____|/   \(     )/    \( |_____|/     \(          )/       \(      )/  
    '   )/       '     '      '    )/         '          '         '      '   
        '                          '                                          
```

crema is a Wayland compositor that runs on the JVM and renders with Vulkan.
It's a proof of concept that the desktop you rice doesn't have to be written in cluttered C++.
Your compositor, config and plugins can be written in a language you actually
enjoy writing instead of debugging segfaults and link errors.

**Status: early days.** crema is under heavy construction. It boots, it
enumerates hardware, and it renders — but it isn't your daily driver yet.
Everything below is the target experience; treat it as the roadmap, not the
release notes.

## Why would you care?

- **It's a real Wayland compositor on the JVM.** No emulation, no wrapper
  scripts, no "technically a window manager that draws over everything."
  Native GPU rendering through Vulkan, composited and committed the proper way.
- **Extend it in Kotlin or Java.** No C, no C++, no FFI ceremony for your ideas.
  The compositor exposes a plugin API, so your status bar, your animations,
  your window rules, your window tiling strategies are just jar files in a plugins folder.
- **Vulkan rendering.** Unlike most popular compositors which use OpenGL, Crema is built for optimal usage 
  of your GPU for advanced effects and non-blocking rendering. So you can get that 2k LoC liquid glass shader
  running smoothly.
- **Multi GPU Support from the get-go**: Crema doesn't try to hide the complexity of the user's machine topology, it uses all GPUs on the machine to render to each monitor efficiently.

## Structure

| Folder               | What it does                                                                                                   | Status  |
|----------------------|:---------------------------------------------------------------------------------------------------------------|:-------:|
| app                  | Contains the compositor application logic built on top of core and its implementations                         |   WIP   |
| core                 | Platform agnostic abstractions for rendering and commiting to the screen in Java WORA style                    |   WIP   |
| blit-targets-drm     | Implements DRM as a BlitTarget to be used with core                                                            |  done   |
| blit-targets-wayland | Emulates a monitor as a wayland window, allowing the compositor to be ran in nested mode                       | planned |
| blit-targets-win32   | Similar to wayland but for running on windows (Out of scope for the MVP, but it will be done as an experiment) | planned |
| buildSrc             | Helpers for gradle build scripts                                                                               |   N/A   |
| drm-sys              | Auto generated libdrm bindings using jextract integrated into gradle                                           |  done   |
| utils                | Utility functions and classes used across all packages                                                         |  done   |
| lwjgl-utils          | Kotlin helpers for lwjgl                                                                                       |  done   |
| vulkan-renderer      | Renderer implementation using Vulkan                                                                           |   WIP   |
| plugin-manager       | Plugin manager and loader                                                                                      | planned |


## Running it

### Requirements

- Linux/BSD (for native mode) or a desktop where you can open a window (nested mode)
- A GPU with a Vulkan driver
- JDK 26+ and the Gradle wrapper

### Build

```sh
./gradlew build
```

To run it interactively, use `scripts/run` (builds and launches the app
directly):

```sh
scripts/run
```

Use this instead of `./gradlew run`: Gradle forks the app JVM into its own
process group, so Ctrl+C (SIGINT to the terminal's foreground group) never
reaches the compositor and its shutdown hook can't run — the screen stays
captured. Launching the start script directly keeps the JVM in the
foreground group, so Ctrl+C tears the display down cleanly and returns you
to the console.

### Presentation modes

crema has three personalities, chosen automatically at startup:

| Mode                 | What you get                                          | Where                                                 |
|----------------------|-------------------------------------------------------|-------------------------------------------------------|
| **Native (DRM/KMS)** | A bare-metal compositor driving your monitor directly | The real deal — this is the ricer mode                |
| **Nested (Wayland)** | The compositor running inside a normal Wayland window | Dev/demo mode: hack on it without nuking your session |
| **Nested (Win32)**   | Same idea, for Windows                                | For the sickos (planned)                              |

Native mode is what you'd use from your login manager or a manual VT switch.
Nested mode is where you experiment safely while your current desktop keeps
running underneath.

## Customizing

The whole point is that this thing is yours.

- **Plugins**: the compositor exposes a plugin API for Kotlin and Java. Window
  management rules, effects, bars, overlays — write them once, ship them as
  libraries, drop them in.
- **Config**: user-facing configuration is coming. The goal is config that
  reads like a dotfile, not a serialization test suite.
- **Everything is composable**: because the compositor is built from
  interchangeable pieces, swapping in your own behavior is a first-class
  operation rather than a fork-and-pray.

## Safety

- **Do not run native mode on a machine you care about yet.** It grabs the
  display directly. If something goes wrong you'll be staring at a frozen TTY
  (or worse). Nested mode is your friend.
- This is a proof of concept with a healthy appetite for `TODO()`.
- You have been warned. Rice responsibly.

## Performance

JVM has an historical bad reputation for being a memory hog and slow, but that reputation comes from old java versions like Java 7 and 8.
But those performance problems have been solved in newer versions:

- Newer java versions ship better garbage collection algorithms which are heap size independent and can run concurrently with sub millisecond pauses which is a big improvement.
- JIT compiles hot paths to machine code at runtime, making them run at speeds that resemble systems languages like Rust or C++.
- Java is battle-tested in backend and server workloads for years now that have a lot of CPU-bound and IO-bound operations.

Kotlin also provides good async support with coroutines, which were integrated into crema for non-blocking VSync support and structured concurrency,
so crema doesn't need to spawn OS threads for each monitor, the kotlin runtime manages wait points instead of doing like most C and C++ compositors
that use a manual error-prone harder-to-reason-about event-loop.


## Development

- `scripts/run` — build and run (recommended for interactive use, see above)
- `./gradlew build` — build everything
- `./gradlew check` — all checks and tests
- `./gradlew clean` — clean build outputs

The project uses the Gradle wrapper (`./gradlew`), a version catalog
(`gradle/libs.versions.toml`), and shared build logic in `buildSrc`.