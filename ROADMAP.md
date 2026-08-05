# crema — MVP roadmap

> **Definition of MVP:** a compositor you can use as a daily driver — launch apps,
> type, click, copy/paste, move/resize windows, run a bar, use multiple monitors,
> rebind keys, and recover from crashes.

Working assumptions baked in: client-side decorations for MVP (via
`xdg-decoration`), integer scaling only, `wlr-layer-shell` for bars.

---

## Phase 0 — Baseline *(done)*

- [x] Boot, backend selection (`Backend.chooseBackend`), DRM connector enumeration, clear + present per output
- [x] Clean `core` seams: `Renderer`, `BlitTarget`, `InputManager`, `BackendConfig` (the future plugin boundaries)

## Phase 1 — The modern-JVM story *(cheap, do anytime)*

- [X] README *Requirements*: explain **why** JDK 26+ — modern low-pause collectors (generational Shenandoah, ZGC) + FFM for the native bindings
- [X] README *Why would you care?*: bullet on generational Shenandoah (default since JDK 23) keeping GC pauses a small fraction of a 16.6 ms frame, tuned for the short-lived objects a compositor allocates every frame
- [X] `app/build.gradle.kts`: add `-XX:+UseShenandoahGC` to `applicationDefaultJvmArgs` so the claim is literally true at launch

## Phase 2 — Renderer can composite (de-risk the pipeline before Wayland exists)

- [ ] Minimal Vulkan graphics pipeline: render pass, framebuffer, pipeline layout, descriptor set, fullscreen quad buffers
- [ ] GLSL→SPIR-V shaders compilation pipeline in gradle
- [ ] Extend `FrameRecording` with a draw op: `draw(image, srcRect, dstRect)`
- [ ] **Demo:** hardcoded generated texture on screen instead of clear-red

## Phase 3 — Wayland server core (new `wayland` module)

- [ ] Wire format: header + payload marshalling (endianness, 32-bit alignment, fd passing), per-connection read buffer
  - **Decision:** hand-rolled transport in Kotlin (not libwayland). libwayland is protocol logic in C — contradicts the "natives as thin OS/GPU interfaces" premise; the wire layer is ~200 lines of spec. Proven precedent: Smithay/`wayland-rs`.
  - **Decision:** XML-driven codegen is a public tool from day one — the codegen built for in-tree protocols doubles as the plugin authoring tool. It is *build-time*; the runtime never parses XML.
- [ ] Socket plumbing: bind/listen on `$XDG_RUNTIME_DIR`, accept, watch fds via `PollDispatcher` (own loop; also reused for the IPC socket later)
- [ ] Object model: id space, object registry, dispatch table, event sending
- [ ] Minimal protocol objects: `wl_display`, `wl_registry`, `wl_compositor`, `wl_surface` (attach/damage/commit/frame), `wl_shm` + `wl_shm_pool`, `wl_buffer` (release), `wl_callback` (done), minimal `wl_output`
- [ ] `wl_display.error` / `delete_id` semantics so clients can roundtrip
- [ ] Set up the "client zoo" test harness (weston-simple-shm, GTK, Qt) — compatibility is proven empirically; there is no Wayland conformance suite

## Phase 4 — Scene + surface model

- [ ] Shared scene: ordered surface list (pos/size/damage/buffer), lock-protected; written by wayland dispatch, read by render loops
- [ ] Commit handling: `wl_surface.commit` takes the attached buffer, marks damage, releases the previous buffer
- [ ] Upload client shm (mmap received fd) → Vulkan image (`vkCmdCopyBufferToImage` or staged copy); draw with the Phase 2 pipeline
- [ ] `wl_buffer.release` once the buffer is no longer needed
- [ ] **Demo:** bundled test client (tiny Kotlin, hand-rolled client marshalling) draws a gradient into wl_shm, attaches fullscreen → visible on the VT

## Phase 5 — E2E polish (vertical slice done)

- [ ] Frame-callback pacing (`wl_callback.done` after present) — required for animating clients
- [ ] Damage tracking; skip work for undamaged surfaces
- [ ] Client disconnect cleanup: destroy surfaces, free uploads

## Phase 6 — Outputs & geometry (multi-monitor)

- [ ] Full `wl_output` (geometry/mode/scale/done) + `xdg-output` (logical geometry)
- [ ] Integer scaling (`wl_output.scale` 1/2), scaled surfaces
- [ ] Multi-monitor layout: positioning, per-output render loops (exist), hotplug re-arrange
- [ ] Wallpaper/background color fill behind windows

## Phase 7 — Input (libinput + `wl_seat`)

- [ ] `libinput` backend module implementing `InputManager` (udev/seat probing)
- [ ] `wl_seat` + capabilities; `wl_pointer`, `wl_keyboard`, `wl_touch` objects
- [ ] Keyboard: xkbcommon keymap, focus, key events, **key repeat**
- [ ] Pointer: enter/leave/motion/button/axis, pointer focus
- [ ] Touch: basic touch sequences (stretch)

## Phase 8 — Shell: `xdg-shell`

- [ ] `xdg_wm_base` + ping/pong
- [ ] `xdg_toplevel`: configure/ack_configure state machine, title/icon, maximize, fullscreen, close, move/resize requests
- [ ] `xdg_popup` + `xdg_positioner` + grabs
- [ ] `xdg-decoration-unstable-v1` (client-side decorations path)
- [ ] **Surface role enforcement** — the part that keeps the protocol state machine honest
- [ ] Adopt XML-driven codegen before adding further protocols — never hand-maintain a protocol again

## Phase 9 — Window management + scene layers

- [ ] Scene layers: background / panel / windows / overlay, with stacking & focus
- [ ] Move/resize (drag + keyboard), fullscreen, close, minimize (optional)
- [ ] Focus model: click-to-focus, focus-follows-mouse (optional), raise-on-click
- [ ] Basic workspaces (optional)
- [ ] Server-side cursor: cursor theme, hotspot, rendered as a scene layer

## Phase 10 — Clipboard (`wl_data_device`)

- [ ] `wl_data_device_manager`, data sources/offers, MIME handling
- [ ] Copy/paste between clients; selection ownership lifecycle
- [ ] Drag & drop (stretch); primary selection (stretch)

## Phase 11 — Bars & panels (`wlr-layer-shell`)

- [ ] `wlr-layer-shell` protocol: layer surfaces, anchors, margins, exclusive zones, keyboard interactivity
- [ ] **Verified with a real client** (waybar/eww) — the "rice it" milestone

## Phase 12 — Config, keybindings & IPC control surface

- [ ] User config: outputs/layout, keybinds, background color, exec-on-startup
- [ ] Keybind dispatch: launch apps, toggle fullscreen, focus next, close, switch output
- [ ] `exec` support (spawn a terminal/app from a keybind or config)
- [ ] **IPC control surface:** unix socket at `$XDG_RUNTIME_DIR/crema/crema.sock` (0700 dir — per-user access control, same model as sway)
  - **Decision:** unix socket, not WebSocket/TCP — a TCP localhost control port is reachable by any local user's processes, and the API carries `exec`-grade power
- [ ] Shared `crema-ipc` contract module: `@Serializable` sealed `Command`/`Response` types + protocol version field (tRPC-style type safety by sharing code)
- [ ] Framing: length-prefixed JSON with request IDs (kotlinx.serialization is already in the catalog)
- [ ] `jayctl` CLI (Clikt) as the reference client; event subscriptions as a stream over the same socket
- [ ] Plugin command registry — plugins register IPC commands, the CLI grows with the system

## Phase 13 — Robustness: safe mode + supervision

- [ ] Supervisor process: relaunch on non-clean exit with `--safe-mode`
- [ ] `--safe-mode`: system plugins only (no user plugins), boots to a plain working desktop; also the manual recovery path (`jayctl safe-mode`)
- [ ] Crash-loop guard: stop relaunching after repeated safe-mode failures, hand the console back
- [ ] VT switching / clean Ctrl+C teardown (partially exists)
- [ ] *(Stretch)* Launch from a login manager (greetd/ly) with session takeover

## Phase 14 — Runtime plugin system (Bukkit-style, out-of-tree) — *the extensibility payoff, last*

**API surface (`plugin-api` module)**
- [ ] `CremaPlugin` base class: `onLoad()` → `onEnable()` → `onDisable()`, per-plugin `getDataFolder()`
- [ ] Event bus: `@EventHandler`, priority ordering, cancellation — dispatched on the event-loop thread, never blocking the frame path
- [ ] Services manager: plugins provide/consume APIs
- [ ] Scheduler: delayed/repeating tasks + frame-callback-synced tasks
- [ ] Validated mutation API — plugins control the compositor *through* the API only; invariants checked, partial handler effects roll back on exception

**Plugin manager (`plugin-manager` module)**
- [ ] Discovery: `plugins/` folder, Bukkit-style descriptor (name, main, `api-version`, `depend`/`softdepend`, `trusted`)
- [ ] Isolated per-plugin classloader (parent = app classloader; system plugins share it for native libs — LWJGL first-loader-wins convention)
- [ ] Dependency-graph resolution, enable/disable ordering
- [ ] Health tracking: exception isolation per plugin, `PluginDisableEvent`, watchdog for wedged event-loop threads
- [ ] `api-version` enforcement: refuse plugins built against a different API

**Three plugin kinds — all runtime jars with build-time tooling**
- [ ] **Behavior plugins:** WM rules, bars, effects, overlays
- [ ] **System plugins:** `vulkan-renderer`, `blit-targets-*`, input backends ship as runtime-discovered, trusted plugins — drop in/out without touching core; `Backend.chooseBackend` fallback logic moves into plugin discovery; policy for "no renderer/blit target found" (fail clearly or fall back to a built-in stub)
- [ ] **Protocol plugins:** plugins implement `wl_registry` globals and dispatch. Generated typed bindings (from protocol XML) + handlers ship compiled in the jar; **XML is build data, never shipped** — published publicly so clients/tooling can generate from it; descriptor carries interface name(s)/versions/`trusted`
- [ ] The in-tree protocol codegen (Phase 3/8) is the same public tool plugin authors use

**Safe mode + recovery (ties into Phase 13)**
- [ ] Protocol plugins and system plugins are `trusted`; safe mode loads trusted only
- [ ] Throwing protocol handlers disable that plugin, never the compositor

**Out-of-tree development**
- [ ] Publish the `plugin-api` jar (Maven Central or a simple repo) so plugins build without the crema source tree
- [ ] Plugin archetype/template project: Gradle setup, depends only on `crema-plugin-api`
- [ ] Install = drop the jar into `plugins/`; delete = remove it (documented, demo'd with a real plugin)

---

## Post-MVP (explicitly not in MVP)

Fractional scaling, server-side decorations, IME/text-input, session lock,
screencopy/screenshots, VRR, touch gestures, per-window effects, drag-drop
(stretch), primary selection (stretch), config hot-reload (stretch), a
WebSocket/web panel as a *plugin* (the IPC surface is unix-socket for security;
a plugin may expose its own transport).

## Key design decisions locked in

- **No libwayland.** Transport is hand-rolled Kotlin (~200 lines of spec); protocol objects are XML-codegen-generated at build time. libwayland is a big slab of protocol logic in C, which contradicts the project's premise.
- **Codegen is a public tool.** The same generator serves in-tree protocols and out-of-tree protocol plugins. Jars ship compiled classes; XML is build data, published for clients.
- **Typed RPC, tRPC-style.** IPC type safety comes from sharing the contract module between compositor and `jayctl`, not from a schema or codegen.
- **Plugins are trusted third-party code.** All plugin calls go through a validated, rollback-capable API; per-plugin exception isolation; safe mode = system plugins only.
- **Supervisor, not in-JVM restart.** A corrupted JVM with captured DRM state can't reliably heal itself; recovery is a fresh process with `--safe-mode`.
