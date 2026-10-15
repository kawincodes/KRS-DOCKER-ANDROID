#!/usr/bin/env bash
# Populates app/src/main/jniLibs/<abi>/ with the executables the app runs without root:
#   libqemu.so   QEMU system emulator (from Termux packages) + its shared libraries
#   libdocker.so static Docker CLI (from download.docker.com)
# and app/src/main/assets/qemu/ with QEMU firmware ROMs.
#
# Android only allows exec() from the app's nativeLibraryDir, and only lib*.so files are
# extracted there, hence the naming. Requires: python3, readelf, patchelf, curl, tar.
#
#   ABIS="arm64-v8a x86_64" DOCKER_CLI_VERSION=29.9.1 scripts/fetch-native.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ABIS="${ABIS:-arm64-v8a x86_64}"
DOCKER_CLI_VERSION="${DOCKER_CLI_VERSION:-29.9.1}"
CACHE="${NATIVE_CACHE:-.native-cache}"
JNI=app/src/main/jniLibs
ASSETS=app/src/main/assets
mkdir -p "$CACHE" "$JNI" "$ASSETS/qemu"

for ABI in $ABIS; do
  case "$ABI" in
    arm64-v8a) ARCH=aarch64; QPKG=qemu-system-aarch64-headless; QEXE=bin/qemu-system-aarch64 ;;
    x86_64)    ARCH=x86_64;  QPKG=qemu-system-x86-64-headless;  QEXE=bin/qemu-system-x86_64 ;;
    *) echo "unsupported ABI $ABI" >&2; exit 1 ;;
  esac
  rm -rf "${JNI:?}/$ABI"
  echo ">> [$ABI] QEMU ($QPKG)"
  python3 scripts/termux_bundle.py --arch "$ARCH" --abi "$ABI" --package "$QPKG" --exe "$QEXE" \
    --exe-name libqemu.so --out "$JNI" --cache "$CACHE" \
    --data share/qemu:qemu \
    --data-filter '^(bios-256k\.bin|linuxboot_dma\.bin|linuxboot\.bin|multiboot_dma\.bin|pvh\.bin|kvmvapic\.bin|efi-virtio\.rom|efi-e1000\.rom)$' \
    --assets "$ASSETS"

  echo ">> [$ABI] Docker CLI $DOCKER_CLI_VERSION"
  TGZ="$CACHE/docker-$ARCH-$DOCKER_CLI_VERSION.tgz"
  [ -s "$TGZ" ] || curl -fsSL -o "$TGZ" "https://download.docker.com/linux/static/stable/$ARCH/docker-$DOCKER_CLI_VERSION.tgz"
  tar -xzf "$TGZ" -O docker/docker > "$JNI/$ABI/libdocker.so"
  chmod 755 "$JNI/$ABI/libdocker.so"
  STRIP="$(command -v llvm-strip || ls "${ANDROID_NDK_HOME:-/nonexistent}"/toolchains/llvm/prebuilt/*/bin/llvm-strip 2>/dev/null | head -1 || true)"
  if [ -n "$STRIP" ]; then "$STRIP" "$JNI/$ABI/libdocker.so"; else echo "   (llvm-strip not found; docker CLI left unstripped)"; fi
  du -sh "$JNI/$ABI"
done
printf '%s\n' "$DOCKER_CLI_VERSION" > "$ASSETS/qemu/docker-cli-version.txt"
echo ">> done"
