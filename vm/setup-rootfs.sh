#!/bin/sh
# Runs INSIDE an alpine container (native or emulated arch). Turns it into a bootable
# Docker VM root filesystem. Arguments: <serial-console-device> e.g. ttyAMA0 / ttyS0
set -eux
CONSOLE="$1"

# Optional: trust an extra CA during the build only (for TLS-intercepting proxies).
if [ -s /extra-ca.crt ]; then cat /extra-ca.crt >> /etc/ssl/certs/ca-certificates.crt; fi

apk add --no-cache \
  alpine-base openrc busybox-openrc linux-virt \
  docker docker-cli e2fsprogs e2fsprogs-extra blkid \
  ca-certificates iptables ip6tables tzdata

# ---------------------------------------------------------------- init / services
cat > /etc/inittab <<INITTAB
::sysinit:/sbin/openrc sysinit
::sysinit:/sbin/openrc boot
::wait:/sbin/openrc default
${CONSOLE}::respawn:/sbin/getty -n -l /bin/sh -L 115200 ${CONSOLE} vt100
::ctrlaltdel:/sbin/reboot
::shutdown:/sbin/openrc shutdown
INITTAB

for s in devfs dmesg mdev hwdrivers; do rc-update add $s sysinit; done
for s in modules sysctl hostname bootmisc syslog networking; do rc-update add $s boot; done
for s in killprocs savecache mount-ro; do rc-update add $s shutdown; done

sed -i 's/^#\?rc_cgroup_mode=.*/rc_cgroup_mode="unified"/' /etc/rc.conf
sed -i 's/^#\?rc_parallel=.*/rc_parallel="YES"/' /etc/rc.conf

# ---------------------------------------------------------------- filesystem / network
printf '/dev/vda\t/\text4\trw,noatime,discard\t0 1\n' > /etc/fstab
printf 'krs-dockers\n' > /etc/hostname
printf '127.0.0.1\tlocalhost krs-dockers\n::1\tlocalhost\n' > /etc/hosts
cat > /etc/network/interfaces <<IFACES
auto lo
iface lo inet loopback

auto eth0
iface eth0 inet dhcp
IFACES
printf 'nameserver 10.0.2.3\n' > /etc/resolv.conf
printf 'net.ipv4.ip_forward = 1\nvm.overcommit_memory = 1\n' > /etc/sysctl.d/99-krs.conf

# ---------------------------------------------------------------- docker
cat > /etc/conf.d/docker <<DOCKERCONF
# Exposed to the Android app through QEMU user-mode networking (hostfwd to 127.0.0.1 only).
DOCKER_OPTS="--host=unix:///var/run/docker.sock --host=tcp://0.0.0.0:2375 --tls=false"
DOCKER_ULIMIT="-n 1048576"
DOCKERCONF
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<DAEMON
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" },
  "features": { "buildkit": true }
}
DAEMON

# Prints a marker on the console when the API is reachable, so the app can show "ready".
cat > /etc/local.d/krs-ready.start <<'READY'
#!/bin/sh
( for i in $(seq 1 180); do
    if [ -S /var/run/docker.sock ] && docker version >/dev/null 2>&1; then
      echo "KRS-DOCKER-READY $(docker version --format '{{.Server.Version}}')" > /dev/console
      exit 0
    fi
    sleep 1
  done; echo "KRS-DOCKER-FAILED" > /dev/console ) &
READY
chmod +x /etc/local.d/krs-ready.start

# Reports published container ports on the console as "KRS-PORTS tcp:8080 udp:53" whenever
# they change, so the app can forward them to the phone (like Docker Desktop's port forwarding).
cat > /usr/local/bin/krs-ports <<'PORTS'
#!/bin/sh
last="-"
while true; do
  cur=$(docker ps --format '{{.Ports}}' 2>/dev/null | tr ',' '\n' \
        | sed -n 's/.*:\([0-9][0-9]*\)->[0-9][0-9]*\/\(tcp\|udp\).*/\2:\1/p' | sort -u | tr '\n' ' ')
  if [ "$cur" != "$last" ]; then echo "KRS-PORTS $cur" > /dev/console; last="$cur"; fi
  sleep 3
done
PORTS
chmod +x /usr/local/bin/krs-ports
cat > /etc/init.d/krs-ports <<'SVC'
#!/sbin/openrc-run
description="Announce published container ports to the KRS Dockers app"
command="/usr/local/bin/krs-ports"
command_background=true
pidfile="/run/krs-ports.pid"
depend() { need docker; }
SVC
chmod +x /etc/init.d/krs-ports

cat > /etc/motd <<MOTD
KRS Dockers VM (Alpine $(cat /etc/alpine-release), Docker $(docker --version | awk '{print $3}' | tr -d ,))
MOTD

for s in docker local krs-ports; do rc-update add $s default; done

# Root login only on the serial console (autologin); no network logins.
passwd -l root || true

# Trim caches
rm -rf /var/cache/apk/* /tmp/* /root/.cache
