## 1.2.0: built-in ADB, Shizuku-style tasks

### ADB connector built into the app (no root, no computer)
*More → ADB & shell* pairs KRS Dockers with your phone's own **Wireless debugging** (Android 11+). It then gets **shell-user (uid 2000) privileges**, like Shizuku.

- **Pairing from a notification.** Tap *Pair with Wireless debugging*, open *Pair device with pairing code*, and type the 6-digit code into the notification without leaving Settings. Split-screen manual entry also works.
- **Shell service.** After pairing, the app starts its own shell service with `app_process`. Like Shizuku's server, it keeps running as the shell user until the next reboot, even after Wireless debugging is turned off.
- **One-tap tasks:**
  - *Keep Docker VM alive* turns off Android's phantom process killer, so the background VM is no longer killed.
  - *Unrestricted background running* adds the app to the battery allowlist.
  - *Allow notifications*, *Device & kernel info*, *Top processes*, and *KRS Dockers logs*.
  - *Restore phantom process killer* undoes the first task.
- **Custom commands.** Run any shell command and save it as your own task.
- **ADB shell.** An interactive shell runs as the shell user.
- **Docker VM shortcut.** The Docker VM screen now has a *Keep VM alive (via ADB)* button.
- **Android 10 and older.** Run `adb tcpip 5555` once from a computer, then tap *Connect & start*.

Docker itself still runs in the Docker VM. The shell user can't create containers, but ADB makes the VM reliable in the background.

## 1.1.1

- **Root engine now explains incompatible kernels.** If the phone's kernel lacks the PID, IPC or UTS namespaces or device control, the root engine says containers can't run natively and offers the Docker VM instead. Many stock kernels, including 4.4-era Xiaomi "perf" kernels, are affected.
- **Root engine status now checks the Docker API.** "Running" now means the Docker API answers, not just that a `dockerd` process exists.
- **Automatic `vfs` fallback.** When the kernel has no overlayfs, the root engine uses the `vfs` storage driver instead of failing with `overlay2`.
- **Renamed screen.** *Local engine* is now called *Root engine*.

## Highlights (1.1.0)

### Run Docker on your phone, no root needed
KRS Dockers now ships its own engine. Open **Docker VM (no root)** and tap **Download & install**, then **Start Docker**. The app boots a small Alpine Linux virtual machine with **Docker Engine 29** using a bundled QEMU, and connects to it automatically.

- Works on any 64-bit Android phone (arm64) and on x86_64 devices. It does not need root.
- The first setup downloads about 190 MB. After that, boot takes about 1–3 minutes depending on the phone.
- Ports published with `-p` are **forwarded to the phone automatically**, for example `-p 8080:80` serves `http://localhost:8080`. You can optionally expose them to your Wi-Fi.
- You can set the CPUs and memory, auto-start Docker with the app, and use a root console into the VM.
- A notification keeps the engine running while the app is in the background.

### Real terminal
- The terminal is now a full PTY based on Termux's terminal emulator. It supports colors, `vi`, `top`, job control, tabs and an extra-keys row.
- The phone shell starts in the app's home folder and has the **`docker` CLI on PATH**, already pointed at the connected engine.
- Container terminals use `docker exec -it` with a real TTY.
- The bug that showed `can't find tty fd` / `won't have full job control` / `ls: .: Permission denied` is fixed.

### Other changes
- The rooted engine now starts with Docker 27 and newer (`--tls=false` for the local TCP socket).
- Remote and Windows/WSL2 engines are unchanged.

### Downloads
| File | Use it for |
|---|---|
| `krs-dockers-v1.2.0-arm64-v8a.apk` | Almost all phones (recommended) |
| `krs-dockers-v1.2.0-x86_64.apk` | x86_64 devices and emulators |
| `krs-dockers-v1.2.0-armeabi-v7a.apk` | Old 32-bit phones (remote/root engines only) |
| `krs-dockers-v1.2.0.apk` | Universal (all of the above) |

### Known limits
- The VM emulates its CPU in software, so containers run slower than on a PC. Small images (`alpine`, `*-slim`) work best.
- On Android 12+, if the VM stops on its own, run once from a computer: `adb shell settings put global settings_enable_monitor_phantom_procs false`.
