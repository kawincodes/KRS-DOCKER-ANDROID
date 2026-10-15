#!/usr/bin/env bash
# Builds the KRS Dockers VM image for one architecture.
#   vm/build-image.sh <aarch64|x86_64> <out-dir>
# Output: <out-dir>/krs-vm-<arch>.tar containing vmlinuz, initramfs, disk.qcow2, manifest.json
# Requires: docker (with binfmt/qemu-user for foreign arches), mke2fs, qemu-img, sudo or root.
set -euo pipefail
ARCH="${1:?arch}"; OUT="$(mkdir -p "${2:?out}" && cd "$2" && pwd)"
ALPINE="${ALPINE_VERSION:-3.24}"
DISK_SIZE="${DISK_SIZE:-48G}"
VM_VERSION="$(cat "$(dirname "$0")/VERSION")"
case "$ARCH" in
  aarch64) PLATFORM=linux/arm64; CONSOLE=ttyAMA0 ;;
  x86_64)  PLATFORM=linux/amd64; CONSOLE=ttyS0 ;;
  *) echo "unsupported arch $ARCH" >&2; exit 1 ;;
esac
SUDO=""; [ "$(id -u)" = 0 ] || SUDO="sudo"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"; trap '$SUDO rm -rf "$WORK"; docker rm -f "krs-vm-$ARCH" >/dev/null 2>&1 || true' EXIT

echo ">> building rootfs for $ARCH (alpine $ALPINE)"
docker rm -f "krs-vm-$ARCH" >/dev/null 2>&1 || true
CA_MOUNT=(); [ -n "${EXTRA_CA_FILE:-}" ] && CA_MOUNT=(-v "$EXTRA_CA_FILE:/extra-ca.crt:ro")
docker run --name "krs-vm-$ARCH" --platform "$PLATFORM" -v "$HERE/setup-rootfs.sh:/setup.sh:ro" "${CA_MOUNT[@]}" \
  "alpine:$ALPINE" sh /setup.sh "$CONSOLE"
docker export "krs-vm-$ARCH" > "$WORK/rootfs.tar"

mkdir "$WORK/rootfs"
$SUDO tar -C "$WORK/rootfs" -xpf "$WORK/rootfs.tar"
$SUDO rm -f "$WORK/rootfs/.dockerenv"
# docker export leaves these as empty bind-mount placeholders; restore the real files.
printf 'nameserver 10.0.2.3\n' | $SUDO tee "$WORK/rootfs/etc/resolv.conf" >/dev/null
printf 'krs-dockers\n' | $SUDO tee "$WORK/rootfs/etc/hostname" >/dev/null
printf '127.0.0.1\tlocalhost krs-dockers\n::1\tlocalhost\n' | $SUDO tee "$WORK/rootfs/etc/hosts" >/dev/null

$SUDO cp "$WORK/rootfs/boot/vmlinuz-virt" "$WORK/vmlinuz"
$SUDO cp "$WORK/rootfs/boot/initramfs-virt" "$WORK/initramfs"
$SUDO chmod 644 "$WORK/vmlinuz" "$WORK/initramfs"
DOCKER_VERSION="$($SUDO sh -c "ls $WORK/rootfs/lib/apk/db >/dev/null; grep -A1 '^P:docker-engine$' $WORK/rootfs/lib/apk/db/installed | sed -n 's/^V://p'")"
KERNEL_VERSION="$(ls "$WORK/rootfs/lib/modules" | head -1)"

echo ">> creating ext4 ($DISK_SIZE sparse) and converting to compressed qcow2"
truncate -s "$DISK_SIZE" "$WORK/disk.raw"
$SUDO mke2fs -q -t ext4 -L krsroot -E root_owner=0:0 -d "$WORK/rootfs" "$WORK/disk.raw"
qemu-img convert -c -O qcow2 -o cluster_size=65536 "$WORK/disk.raw" "$WORK/disk.qcow2"
rm -f "$WORK/disk.raw"

cat > "$WORK/manifest.json" <<MANIFEST
{
  "version": $VM_VERSION,
  "arch": "$ARCH",
  "alpine": "$ALPINE",
  "kernel": "$KERNEL_VERSION",
  "docker": "${DOCKER_VERSION%%-*}",
  "console": "$CONSOLE",
  "cmdline": "root=/dev/vda rootfstype=ext4 rw modules=ext4,virtio_blk,virtio_pci,virtio_net console=$CONSOLE quiet",
  "diskSize": "$DISK_SIZE"
}
MANIFEST
tar -C "$WORK" -cf "$OUT/krs-vm-$ARCH.tar" manifest.json vmlinuz initramfs disk.qcow2
(cd "$OUT" && sha256sum "krs-vm-$ARCH.tar" > "krs-vm-$ARCH.tar.sha256")
ls -la "$OUT"
cat "$WORK/manifest.json"
