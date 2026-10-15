#!/usr/bin/env python3
"""
Bundle a Termux-built executable and its shared-library closure as Android jniLibs.

Android (targetSdk >= 29) only lets apps exec files from their nativeLibraryDir, and only files
named lib*.so are extracted there. This script:
  1. resolves the Termux package dependency closure of the requested packages,
  2. downloads and unpacks the .deb files,
  3. walks the ELF DT_NEEDED closure starting at the executable,
  4. renames everything to lib*.so (e.g. libz.so.1 -> libz_1.so), rewrites DT_NEEDED/SONAME and
     strips the Termux RUNPATH with patchelf,
  5. copies the results to <out>/<abi>/ and optional data files to <assets>.

Usage:
  termux_bundle.py --arch aarch64 --abi arm64-v8a --package qemu-system-aarch64-headless \
      --exe bin/qemu-system-aarch64 --exe-name libqemu.so --out app/src/main/jniLibs \
      --data share/qemu:qemu --assets app/src/main/assets --cache .native-cache
"""
import argparse, io, lzma, os, re, shutil, subprocess, sys, tarfile, urllib.request

REPO = "https://packages.termux.dev/apt/termux-main"
PREFIX = "data/data/com.termux/files/usr/"
# Android system libraries that must NOT be bundled (provided by the platform linker namespace).
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so_system",
               "libEGL.so", "libGLESv2.so", "libvulkan.so", "libmediandk.so", "libOpenSLES.so",
               "libaaudio.so", "libjnigraphics.so", "libnativewindow.so", "libsync.so", "libcamera2ndk.so"}


def http_get(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def parse_packages(text):
    pkgs, provides = {}, {}
    for block in text.split("\n\n"):
        d = {}
        for line in block.splitlines():
            if ":" in line and not line.startswith(" "):
                k, v = line.split(":", 1)
                d[k] = v.strip()
        if "Package" in d:
            pkgs[d["Package"]] = d
            for p in d.get("Provides", "").split(","):
                p = re.sub(r"\(.*?\)", "", p).strip()
                if p:
                    provides.setdefault(p, d["Package"])
    return pkgs, provides


def dep_names(field):
    out = []
    for part in field.split(","):
        alt = part.split("|")[0]
        name = re.sub(r"\(.*?\)", "", alt).strip()
        if name:
            out.append(name)
    return out


def closure(pkgs, provides, roots):
    seen, todo = set(), list(roots)
    while todo:
        n = todo.pop()
        n = n if n in pkgs else provides.get(n, n)
        if n in seen or n not in pkgs:
            continue
        seen.add(n)
        todo += dep_names(pkgs[n].get("Depends", "")) + dep_names(pkgs[n].get("Pre-Depends", ""))
    return seen


def extract_deb(data, dest):
    # ar archive: "!<arch>\n" then 60-byte headers
    assert data[:8] == b"!<arch>\n", "not an ar archive"
    off = 8
    while off < len(data):
        hdr = data[off:off + 60]
        name = hdr[:16].decode().strip().rstrip("/")
        size = int(hdr[48:58].decode().strip())
        body = data[off + 60: off + 60 + size]
        off += 60 + size + (size & 1)
        if name.startswith("data.tar"):
            mode = "r:xz" if name.endswith(".xz") else "r:gz" if name.endswith(".gz") else "r:*"
            if name.endswith(".zst"):
                body = subprocess.run(["zstd", "-dc"], input=body, capture_output=True, check=True).stdout
                mode = "r:"
            with tarfile.open(fileobj=io.BytesIO(body), mode=mode) as t:
                t.extractall(dest, filter="tar") if sys.version_info >= (3, 12) else t.extractall(dest)


def needed(path):
    out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True).stdout
    return re.findall(r"\(NEEDED\)\s+Shared library: \[(.+?)\]", out)


def android_name(lib):
    """libz.so.1 -> libz_1.so ; libfoo.so -> libfoo.so ; libbar-2.0.so.0 -> libbar-2.0_0.so"""
    m = re.match(r"^(lib.+?)\.so((?:\.\d+)*)$", lib)
    if not m:
        raise ValueError(f"unexpected library name {lib}")
    base, ver = m.group(1), m.group(2)
    return f"{base}{'_' + ver[1:].replace('.', '_') if ver else ''}.so"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arch", required=True)
    ap.add_argument("--abi", required=True)
    ap.add_argument("--package", action="append", required=True)
    ap.add_argument("--exe", required=True, help="path of the executable relative to the Termux prefix")
    ap.add_argument("--exe-name", required=True, help="output name, must be lib*.so")
    ap.add_argument("--out", required=True)
    ap.add_argument("--data", action="append", default=[], help="prefix-relative-dir:asset-subdir")
    ap.add_argument("--data-filter", default="", help="regex of data file names to keep")
    ap.add_argument("--assets", default="")
    ap.add_argument("--cache", default=".native-cache")
    a = ap.parse_args()

    cache = os.path.join(a.cache, a.arch)
    root = os.path.join(cache, "root")
    os.makedirs(root, exist_ok=True)
    idx_path = os.path.join(cache, "Packages")
    if not os.path.exists(idx_path):
        open(idx_path, "wb").write(http_get(f"{REPO}/dists/stable/main/binary-{a.arch}/Packages"))
    pkgs, provides = parse_packages(open(idx_path, encoding="utf-8").read())
    want = closure(pkgs, provides, a.package)
    print(f"[{a.abi}] {len(want)} packages in dependency closure", file=sys.stderr)
    for n in sorted(want):
        fn = pkgs[n]["Filename"]
        deb = os.path.join(cache, os.path.basename(fn))
        if not os.path.exists(deb):
            open(deb, "wb").write(http_get(f"{REPO}/{fn}"))
        marker = deb + ".extracted"
        if not os.path.exists(marker):
            extract_deb(open(deb, "rb").read(), root)
            open(marker, "w").close()

    usr = os.path.join(root, PREFIX)
    libdirs = [os.path.join(usr, "lib")]
    exe = os.path.join(usr, a.exe)
    assert os.path.exists(exe), f"missing {exe}"

    def find_lib(name):
        for d in libdirs:
            p = os.path.join(d, name)
            if os.path.exists(p):
                return os.path.realpath(p)
        return None

    # ELF closure
    libs, todo, missing = {}, list(needed(exe)), set()
    while todo:
        n = todo.pop()
        if n in libs or n in SYSTEM_LIBS or n in missing:
            continue
        p = find_lib(n)
        if p is None:
            missing.add(n)  # resolved by the Android system (libc, libm, libdl, liblog, ...)
            continue
        libs[n] = p
        todo += needed(p)
    print(f"[{a.abi}] system-provided: {sorted(missing)}", file=sys.stderr)

    outdir = os.path.join(a.out, a.abi)
    os.makedirs(outdir, exist_ok=True)
    rename = {n: android_name(n) for n in libs}

    def patch(src, dst, soname=None):
        shutil.copyfile(src, dst)
        os.chmod(dst, 0o755)
        args = ["patchelf", "--remove-rpath"]
        for old in needed(dst):
            if old in rename and rename[old] != old:
                args += ["--replace-needed", old, rename[old]]
        if soname:
            args += ["--set-soname", soname]
        subprocess.run(args + [dst], check=True)

    for n, p in sorted(libs.items()):
        patch(p, os.path.join(outdir, rename[n]), soname=rename[n])
    patch(exe, os.path.join(outdir, a.exe_name))

    total = sum(os.path.getsize(os.path.join(outdir, f)) for f in [rename[n] for n in libs] + [a.exe_name])
    print(f"[{a.abi}] wrote {a.exe_name} + {len(libs)} libs ({total / 1048576:.1f} MiB) to {outdir}", file=sys.stderr)

    keep = re.compile(a.data_filter) if a.data_filter else None
    for spec in a.data:
        rel, sub = spec.split(":", 1)
        src = os.path.join(usr, rel)
        dst = os.path.join(a.assets, sub)
        os.makedirs(dst, exist_ok=True)
        for f in sorted(os.listdir(src)):
            fp = os.path.join(src, f)
            if os.path.isfile(fp) and (keep is None or keep.search(f)):
                shutil.copyfile(fp, os.path.join(dst, f))
        print(f"[{a.abi}] data {rel} -> {dst}", file=sys.stderr)


if __name__ == "__main__":
    main()
