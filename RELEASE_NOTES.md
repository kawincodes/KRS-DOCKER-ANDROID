## Highlights

Docker Desktop-style manager for Android built with Jetpack Compose, shadcn/ui-style components and Lucide icons.

### Downloads
- **krs-dockers-v1.0.0.apk** – optimized release build (R8 minified, ~1.8 MB). Recommended.
- **krs-dockers-v1.0.0-debug.apk** – debug build (package `com.krstech.dockers.debug`), can be installed side by side.

Enable *Install from unknown sources* and open the APK on the phone. Android 8.0+ (arm64 / x86_64 / armv7).

### What's inside
- Containers: run, start/stop/restart/kill/pause, logs, inspect, live stats, processes, interactive terminal, rename, prune
- Images: pull with layer progress, Docker Hub search, registry login, tag, prune, inspect
- Volumes and Networks: create, inspect, remove, prune
- Stacks: deploy and manage `docker compose` projects (compose runs in a `docker:cli` helper container on the engine host)
- Distros (WSL-style): install Ubuntu / Debian / Alpine / Fedora / Arch / Kali / openSUSE as persistent environments, open a shell, export/import tarballs
- Engines: local rooted `dockerd` (installs official static binaries, kernel checks, foreground service), Windows / WSL2 Docker Desktop over LAN (with scanner), remote `tcp://` hosts with TLS client certs
- Live events, device shell, light/dark theme

### Notes
- Running Docker **on the phone itself** requires root (Magisk/KernelSU) and a kernel with namespaces, cgroups and netfilter. The *Local engine* screen checks this. Unrooted devices can use any remote or WSL2 engine.
- Port 2375 is unauthenticated; use it on a trusted LAN or switch to 2376 with TLS.
