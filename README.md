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
enumerates hardware, and it renders, but it isn't your daily driver yet.
Everything below is the target experience; treat it as the roadmap, not the
release notes.

## Why would you care?

- **It's a real Wayland compositor on the JVM.** No emulation, no wrapper
  scripts, no "technically a window manager that draws over everything."
  It does actual native GPU rendering through platform-agnostic abstractions made
  to run everywhere from a normal linux distro to a fridge, composited and
  committed the proper way.
- **Extend it in Kotlin or Java.** No C, no C++, no FFI ceremony for your ideas.
  The compositor exposes a plugin API, so your status bar, your custom animations,
  your window rules, your window tiling strategies are just jar files in a plugins folder instead of Lua scripts and config files with poor DX.
- **Vulkan rendering.** Unlike [popular compositors which use OpenGL](https://github.com/hyprwm/Hyprland/issues/1396),
  Crema is built for optimal usage of your GPU for advanced effects and
  non-blocking rendering. So you can get that 2k LoC liquid glass shader running
  smoothly.
- **Multi GPU support from the get-go.** Crema doesn't shy away from the
  complexity of the user's machine topology, it embraces it. It uses all GPUs on
  the machine to render to each monitor efficiently.

## Structure

| Folder               | What it does                                                                                                   | Status  |
|----------------------|:---------------------------------------------------------------------------------------------------------------|:-------:|
| app                  | Contains the compositor application logic built on top of core and its implementations                         |   WIP   |
| core                 | Platform agnostic abstractions for rendering and committing to the screen in Java WORA style                   |   WIP   |
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

To run it interactively, use `./gradlew run` (builds and launches the app
directly):

```sh
./gradlew run
```

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
  management rules, effects, bars, overlays, rendering backends, anything; write
  them once, ship them as libraries, drop them in.
- **Config**: user-facing configuration is coming. It will be inspired a bit by
  minecraft server configurations, you have a main configuration file and then
  each plugin may have its own.
- **Everything is composable**: because the compositor is built from
  interchangeable pieces, swapping in your own behavior is a first-class
  operation rather than a fork-and-pray.

## Safety

- **Do not run native mode on a machine you care about yet.** It grabs the
  display directly. If something goes wrong you'll be staring at a frozen TTY
  (or worse). Nested mode is your friend.
- This is a proof of concept with a healthy appetite for `TODO()`.
- I am a Vulkan beginner, and I'm using this project as an excuse to learn Vulkan.
- You have been warned. Rice responsibly.

## Performance

The JVM's reputation for being a memory hog and slow dates back to old versions
like Java 7 and 8. Newer versions have largely fixed that:

- Modern JVMs ship garbage collectors like Generational ZGC that are heap size
  independent, run concurrently, and keep pause times consistently in the
  sub-millisecond range.
- The JIT compiles hot paths to machine code at runtime, closing most of the gap
  to native code.

Kotlin coroutines provide non-blocking VSync support with structured
concurrency: instead of blocking one OS thread per monitor, the runtime
suspends and resumes at wait points.


## Development

- `./gradlew run` — build and run
- `./gradlew build` — build everything
- `./gradlew check` — all checks and tests
- `./gradlew clean` — clean build outputs

The project uses the Gradle wrapper (`./gradlew`), a version catalog
(`gradle/libs.versions.toml`), and shared build logic in `buildSrc`.