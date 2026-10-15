# KRS Dockers

**Docker Desktop for Android.** A Jetpack Compose app (`com.krstech.dockers`) that runs a Docker engine on a rooted phone, or drives any remote Docker daemon, including Docker Desktop's WSL2 backend on Windows, with a Docker Desktop-style UI.

| | |
|---|---|
| UI | Jetpack Compose + shadcn/ui-style component library (`ui/components`, zinc tokens) + [Lucide icons](https://lucide.dev) via `com.composables:icons-lucide` |
| Engine | Docker Engine REST API v1.43 over OkHttp: `tcp://`, `tcp://` + TLS client certs, `unix://` (Android `LocalSocket`) |
| Min SDK | 26 (Android 8.0) · target 35 |

## Features

**Containers** list/search/filter, run (image, name, ports, env, binds, command, restart policy, network, tty, privileged, `--rm`), start / stop / restart / kill / pause / unpause / rename / remove, prune. Detail view with live **logs** (stdcopy demuxed), **inspect** JSON, live **stats** (CPU %, memory, net & block I/O, `top`), and an interactive **terminal** (`docker exec -it` over a hijacked connection).

**Images** list, pull with per-layer progress (Docker Hub search, private registry credentials via `X-Registry-Auth`), tag, remove, prune, inspect, run.

**Volumes** and **Networks** create / inspect / remove / prune, usage and attachment counts.

**Stacks (Compose)** projects grouped by `com.docker.compose.project`. Deploy a `compose.yaml` from the phone: the compose CLI runs inside a throw-away `docker:cli` helper container on the engine host (socket mounted, file uploaded with the archive API), so nothing has to be installed on the phone. `up`, `pull & up`, `down`, validate.

**Distros (WSL-style)** install Ubuntu / Debian / Alpine / Fedora / Arch / Kali / openSUSE as persistent Linux environments (container + home volume), open a shell, export to a `.tar` (`wsl --export`) and import a rootfs tarball (`wsl --import`).

**Engines**
- **Local (root)**: requirement checks (su, kernel config, overlayfs, cgroups, iptables), download the official static Docker binaries from `download.docker.com` for the device arch, bootstrap cgroups and start `dockerd` under a foreground service. Options: TCP port, storage driver, iptables, extra flags, daemon log viewer.
- **Windows / WSL2**: connect to Docker Desktop (or `dockerd` inside a WSL2 distro) over the LAN, with a one-tap `/24` scanner for ports 2375/2376 and an in-app setup guide.
- **Remote**: any `tcp://host:port`, with `ca.pem` / `cert.pem` / `key.pem` TLS.

**Events** live `docker events` stream; **Settings** theme (system/light/dark), log tail/timestamps, terminal font size; **Device shell** (`sh` / `su`).

## Running Docker on the phone itself: requirements

A Docker daemon needs kernel features that are not exposed to normal Android apps. The **Local engine** therefore requires:

1. **Root** (Magisk or KernelSU). The app only ever calls `su` for the local engine.
2. A kernel with `CONFIG_NAMESPACES`, `CONFIG_NET_NS`, `CONFIG_PID_NS`, `CONFIG_CGROUPS` (+ memory/cpu/devices/freezer/pids controllers), `CONFIG_VETH`, `CONFIG_BRIDGE`, netfilter NAT, and ideally `CONFIG_OVERLAY_FS`. The *Requirements* card in the app checks `/proc/config.gz` and tells you what is missing.
3. The bootstrap script (`app/src/main/assets/dockerd-bootstrap.sh`) makes `/` rshared, mounts cgroup v2 (or a v1 hierarchy) under `/sys/fs/cgroup`, and launches `dockerd` with `--data-root` / `--exec-root` inside the app's private storage, listening on `tcp://127.0.0.1:2375` and a unix socket.

If the device is not rooted or the kernel is missing features, use a remote engine (the rest of the app works identically).

## Connecting to Docker Desktop on Windows (WSL2)

1. Docker Desktop → *Settings → General* → **Expose daemon on tcp://localhost:2375 without TLS**.
2. Make it reachable from the phone (PowerShell as admin):
   ```powershell
   netsh interface portproxy add v4tov4 listenport=2375 listenaddress=0.0.0.0 connectport=2375 connectaddress=127.0.0.1
   New-NetFirewallRule -DisplayName "Docker API" -Direction Inbound -LocalPort 2375 -Protocol TCP -Action Allow
   ```
   Alternatively, inside your WSL2 distro: `sudo dockerd -H unix:///var/run/docker.sock -H tcp://0.0.0.0:2375` (with WSL *mirrored* networking or a portproxy).
3. In the app: *Engines → + → Windows / WSL2* → **Scan LAN** (or type `tcp://<pc-ip>:2375`) → *Test* → *Save & connect*.

Port 2375 is unauthenticated; keep it on a trusted LAN or use 2376 with TLS (`dockerd --tlsverify …`) and paste the PEM files into the engine profile.

## Build

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

Requires JDK 17+ and the Android SDK (platform 35). GitHub Actions (`.github/workflows/android.yml`) builds and uploads a debug APK on every push.

## Project layout

```
app/src/main/java/com/krstech/dockers
├── DockersApp.kt / MainActivity.kt       application container, Compose root + bottom nav
├── engine/
│   ├── DockerClient.kt                   Engine API (containers, images, volumes, networks, exec, events, stats, archive)
│   ├── DockerHost.kt                     tcp / tls / unix host model, raw socket for hijack
│   ├── EngineManager.kt                  active connection state + shared event stream
│   ├── model/Models.kt                   kotlinx.serialization API models
│   ├── transport/                        PEM/TLS, Unix socket factory, stdcopy demux, hijacked HTTP upgrade, tar
│   ├── local/                            RootShell, LocalEngine (install/start/stop dockerd), EngineService (FGS)
│   ├── compose/ComposeRunner.kt          compose via docker:cli helper container
│   ├── distro/DistroManager.kt           WSL-style distros
│   ├── wsl/LanScanner.kt                 LAN discovery of daemons
│   └── profile/                          engine profiles (DataStore)
└── ui/
    ├── theme/ShadTheme.kt                shadcn zinc tokens → Compose/Material3
    ├── components/                       Button, Card, Badge, Input, Switch, Tabs, Select, Menu, Dialog, Sheet, Progress, Skeleton…
    └── screens/                          dashboard, containers, images, volumes, networks, stacks, distros, terminal, engines, settings, events
```

## Disclaimer

Docker and the Docker logo are trademarks of Docker, Inc. KRS Dockers is an independent project by KRS Tech Solutions and is not affiliated with Docker, Inc.

## Releases

Pushing a tag like `v1.0.0`, changing the `VERSION` file, or running the workflow manually with a version input runs `.github/workflows/release.yml`, which builds, signs and publishes the APKs to [GitHub Releases](https://github.com/kawincodes/KRS-DOCKER-ANDROID/releases). Add these repository secrets to sign with your own key (otherwise a throwaway key is used): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
