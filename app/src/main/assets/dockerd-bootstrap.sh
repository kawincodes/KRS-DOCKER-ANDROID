#!/system/bin/sh
# KRS Dockers - dockerd bootstrap (runs as root via su)
# Usage: dockerd-bootstrap.sh <bin_dir> <data_root> <exec_root> <tcp_port> <unix_sock> <pid_file> <log_file> <storage_driver> <iptables 0|1> <extra args...>
BIN="$1"; DATA="$2"; EXEC="$3"; PORT="$4"; SOCK="$5"; PIDF="$6"; LOGF="$7"; DRIVER="$8"; IPT="$9"
shift 9
EXTRA="$*"

export PATH="$BIN:/system/bin:/system/xbin:/sbin:/vendor/bin:$PATH"
export TMPDIR="$DATA/tmp"
export DOCKER_RAMDISK=true
mkdir -p "$DATA" "$EXEC" "$TMPDIR" "$(dirname "$SOCK")" "$(dirname "$PIDF")" "$(dirname "$LOGF")"
chmod 755 "$BIN"/* 2>/dev/null

log() { echo "[bootstrap] $*" >> "$LOGF"; }
log "starting at $(date)"

# --- Make the root mount shared so overlay/bind mounts propagate (Docker requirement) ---
mount --make-rshared / 2>/dev/null || log "warn: could not make / rshared"

# --- cgroups: Android mounts controllers in odd places. Try cgroup v2 first, then v1 layout. ---
if [ ! -d /sys/fs/cgroup ]; then mkdir -p /sys/fs/cgroup 2>/dev/null; fi
if ! mountpoint -q /sys/fs/cgroup 2>/dev/null; then
  if grep -q cgroup2 /proc/filesystems 2>/dev/null && mount -t cgroup2 none /sys/fs/cgroup 2>/dev/null; then
    log "mounted cgroup2 at /sys/fs/cgroup"
  else
    mount -t tmpfs -o mode=755,size=1M tmpfs /sys/fs/cgroup 2>/dev/null
    for sys in cpu cpuacct cpuset memory devices freezer pids blkio net_cls net_prio hugetlb; do
      mkdir -p "/sys/fs/cgroup/$sys"
      mount -n -t cgroup -o "$sys" cgroup "/sys/fs/cgroup/$sys" 2>/dev/null \
        || rmdir "/sys/fs/cgroup/$sys" 2>/dev/null
    done
    log "mounted cgroup v1 hierarchy"
  fi
fi

# --- Binary sanity ---
if [ ! -x "$BIN/dockerd" ]; then
  log "error: $BIN/dockerd missing or not executable"
  exit 2
fi

# Kill stale daemon from an earlier run
if [ -f "$PIDF" ]; then
  OLD=$(cat "$PIDF" 2>/dev/null)
  [ -n "$OLD" ] && kill "$OLD" 2>/dev/null
  rm -f "$PIDF"
fi
rm -f "$SOCK"

IPTFLAG="--iptables=true --ip6tables=false"
[ "$IPT" = "0" ] && IPTFLAG="--iptables=false --ip6tables=false --ip-forward=false"

log "exec dockerd (driver=$DRIVER port=$PORT)"
exec "$BIN/dockerd" \
  --host "tcp://127.0.0.1:$PORT" --tls=false \
  --host "unix://$SOCK" \
  --data-root "$DATA" \
  --exec-root "$EXEC" \
  --pidfile "$PIDF" \
  --storage-driver "$DRIVER" \
  --userland-proxy-path "$BIN/docker-proxy" \
  --init-path "$BIN/docker-init" \
  --default-runtime runc \
  --add-runtime "runc=$BIN/runc" \
  $IPTFLAG $EXTRA >> "$LOGF" 2>&1
