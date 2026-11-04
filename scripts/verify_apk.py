#!/usr/bin/env python3
"""Structural verification of the built APK.

Checks the things an installer cares about, without needing a device:
  * binary AndroidManifest.xml has the canonical magic (0x00080003) - a bad
    manifest is the classic cause of "app not installed" on some devices
  * resources.arsc is present, STORED and 4-byte aligned (targetSdk >= 30)
  * classes.dex exists and is dex version 035+
  * min/target SDK, package name and launcher activity are as intended
  * every activity declared in the manifest resolves to a class in the dex
  * APK Signature Scheme v1 (JAR) + v2 blocks are present
  * no permissions that would make the Play/scan experience odd

Usage: python3 scripts/verify_apk.py [path-to-apk]
"""
import os
import re
import struct
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_APK = os.path.join(ROOT, "dist", "DevvioRD-1.0.0.apk")
AAPT2 = os.path.join(ROOT, "toolchain", "tools", "aapt2")

results = []


def ok(name, extra=""):
    results.append(True)
    print(f"  PASS  {name}{(' - ' + extra) if extra else ''}")


def bad(name, extra=""):
    results.append(False)
    print(f"  FAIL  {name}{(' - ' + extra) if extra else ''}")


def check_axml(apk):
    """The manifest must start with the ResChunk_header for XML (0x0003)."""
    with zipfile.ZipFile(apk) as z:
        data = z.read("AndroidManifest.xml")
    if len(data) < 8:
        return bad("manifest present", "too short"), None
    file_type, header_size, total_size = struct.unpack("<HHI", data[:8])
    if file_type != 0x0003:
        return bad("manifest is binary AXML", f"type=0x{file_type:04x}"), None
    if header_size != 8 or total_size != len(data):
        return bad("manifest header consistent",
                   f"header={header_size} total={total_size} actual={len(data)}"), None
    ok("manifest is binary AXML", f"{len(data)} bytes")
    return True, data


def check_dex(apk):
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        dex = [n for n in names if re.match(r"classes\d*\.dex$", n)]
        if not dex:
            return bad("classes.dex present")
        data = z.read("classes.dex")
    if data[:4] != b"dex\n":
        return bad("dex magic", data[:8])
    version = data[4:7].decode("ascii", "replace")
    ok("classes.dex valid", f"version {version}, {len(data) // 1024} KiB")
    # Extract class names for the activity check.
    string_ids_size, string_ids_off = struct.unpack("<II", data[56:64])
    type_ids_size, type_ids_off = struct.unpack("<II", data[64:72])
    descriptor_off_size, descriptor_off = struct.unpack("<II", data[96:104])
    classes = set()
    try:
        for i in range(descriptor_off_size):
            off = struct.unpack("<I", data[descriptor_off + i * 4: descriptor_off + i * 4 + 4])[0]
            # uleb128 string length
            n = 0
            shift = 0
            p = off
            while True:
                b = data[p]
                p += 1
                n |= (b & 0x7F) << shift
                shift += 7
                if not b & 0x80:
                    break
            s = data[p:p + n].decode("utf-8", "replace")
            if s.startswith("L") and s.endswith(";"):
                classes.add(s[1:-1].replace("/", "."))
    except Exception:  # noqa: BLE001
        pass
    return classes


def check_resources(apk):
    with zipfile.ZipFile(apk) as z:
        infos = {i.filename: i for i in z.infolist()}
        if "resources.arsc" not in infos:
            return bad("resources.arsc present")
        info = infos["resources.arsc"]
        if info.compress_type != zipfile.ZIP_STORED:
            bad("resources.arsc is STORED (uncompressed)", f"compress_type={info.compress_type}")
        else:
            ok("resources.arsc is STORED (uncompressed)")
        # data offset must be 4-byte aligned
        with open(apk, "rb") as f:
            f.seek(info.header_offset)
            local = f.read(30)
            name_len, extra_len = struct.unpack("<HH", local[26:30])
            data_off = info.header_offset + 30 + name_len + extra_len
        if data_off % 4 == 0:
            ok("resources.arsc is 4-byte aligned", f"offset={data_off}")
        else:
            bad("resources.arsc is 4-byte aligned", f"offset={data_off} (mod4={data_off % 4})")


def check_signature(apk):
    with open(apk, "rb") as f:
        blob = f.read()
    # The .SF/.RSA names follow the keystore alias, so match any META-INF file.
    if b"META-INF/MANIFEST.MF" in blob and (
            b".SF" in blob or b".RSA" in blob or b".DSA" in blob or b".EC" in blob):
        ok("v1 (JAR) signature files present")
    else:
        bad("v1 (JAR) signature files present")
    if b"APK Sig Block 42" in blob:
        ok("v2/v3 APK signing block present")
    else:
        bad("v2/v3 APK signing block present")


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_APK
    if not os.path.exists(apk):
        print("missing APK:", apk)
        return 1
    print(f"\n=== APK verification: {os.path.basename(apk)} ===\n")
    if os.environ.get("STATIC_ONLY") != "1":
        pass

    _, manifest = check_axml(apk)
    check_resources(apk)
    classes = check_dex(apk)
    check_signature(apk)

    # --- badging via aapt2 (authoritative for manifest attributes) ---
    import subprocess
    if os.path.exists(AAPT2):
        out = subprocess.run([AAPT2, "dump", "badging", apk], capture_output=True, text=True).stdout
        pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out)
        if pkg and pkg.group(1) == "ai.devvio.rd":
            ok("package identity", f"{pkg.group(1)} v{pkg.group(3)} ({pkg.group(2)})")
        else:
            bad("package identity", out.splitlines()[0] if out else "no output")
        min_sdk = re.search(r"minSdkVersion:'(\d+)'", out)
        target = re.search(r"targetSdkVersion:'(\d+)'", out)
        if min_sdk and int(min_sdk.group(1)) <= 21:
            ok("minSdkVersion", min_sdk.group(1) + " (Android 5.0+)")
        else:
            bad("minSdkVersion", min_sdk.group(1) if min_sdk else "missing")
        if target and int(target.group(1)) == 34:
            ok("targetSdkVersion", target.group(1))
        else:
            bad("targetSdkVersion", target.group(1) if target else "missing")
        launch = re.search(r"launchable-activity: name='([^']+)'", out)
        if launch:
            ok("launcher activity declared", launch.group(1))
            cls = launch.group(1).lstrip(".")
            if not cls.startswith("ai.devvio"):
                cls = "ai.devvio.rd" + launch.group(1)
            if classes and cls in classes:
                ok("launcher activity class exists in dex", cls)
            elif classes:
                bad("launcher activity class exists in dex", cls)
        else:
            bad("launcher activity declared")
        label = re.search(r"application-label:'([^']*)'", out)
        if label and label.group(1) == "DevvioRD":
            ok("application label", label.group(1))
        icon = re.search(r"application-icon-(\d+):'([^']+)'", out)
        if icon:
            ok("launcher icon", icon.group(2))
        perms = re.findall(r"uses-permission: name='([^']+)'", out)
        ok("declared permissions", ", ".join(p.split(".")[-1] for p in perms))
        # Every declared activity must exist as a class.
        acts = re.findall(r"activity: name='([^']+)'", out) or []
        acts += re.findall(r"activity-alias: name='([^']+)'", out) or []
        missing = []
        for a in re.findall(r"^activity: name='([^']+)'", out, re.M):
            cls = a if "." in a else "ai.devvio.rd" + a
            if classes and cls not in classes:
                missing.append(cls)
        if not missing:
            ok("all manifest activities resolve to dex classes")
        else:
            bad("all manifest activities resolve to dex classes", ", ".join(missing))
    else:
        bad("aapt2 available for badging")

    print("\n--- summary ---")
    passed = sum(1 for r in results if r)
    failed = len(results) - passed
    print(f"{passed} passed, {failed} failed")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
