#!/usr/bin/env python3
"""End-to-end file transfer test against a running DevvioRD server.

Exercises the exact REST API the Android app uses:
  upload/begin -> upload/chunk (1 MiB) -> upload/finish -> files -> download
  plus mkdir / rename / delete and path-traversal protection.

Usage: python3 scripts/files_test.py [--host H] [--port 5900] [--token T]
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

results = []


def ok(name, extra=""):
    results.append(True)
    print(f"  PASS  {name}{(' - ' + extra) if extra else ''}")


def bad(name, extra=""):
    results.append(False)
    print(f"  FAIL  {name}{(' - ' + extra) if extra else ''}")


class Api:
    def __init__(self, base, token):
        self.base = base
        self.token = token or ""

    def _req(self, path, data=None, method=None, ctype="application/json"):
        url = self.base + path
        req = urllib.request.Request(url, data=data, method=method)
        if self.token:
            req.add_header("X-DevvioRD-Token", self.token)
        req.add_header("Content-Type", ctype)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.getcode(), r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except Exception as e:  # noqa: BLE001
            return 0, str(e).encode()

    def get_json(self, path):
        code, body = self._req(path, method="GET")
        try:
            return code, json.loads(body)
        except Exception:  # noqa: BLE001
            return code, None

    def post_json(self, path, obj):
        return self._req(path, json.dumps(obj).encode("utf-8"), method="POST")

    def upload(self, name, data, chunk=1 << 20, d="", resume=False):
        """Mirrors FilesActivity.uploadUri()."""
        code, body = self.post_json("/api/upload/begin",
                                    {"name": name, "dir": d, "size": len(data)})
        if code != 200:
            return None, code, body
        j = json.loads(body)
        up_id = j["id"]
        offset = j.get("offset", 0)
        if resume and offset:
            pass
        while offset < len(data):
            part = data[offset:offset + chunk]
            code, _ = self._req(f"/api/upload/chunk?id={up_id}&offset={offset}", data=part,
                                method="POST", ctype="application/octet-stream")
            if code != 200:
                return None, code, b"chunk failed"
            offset += len(part)
        code, body = self.post_json("/api/upload/finish", {"id": up_id})
        return (json.loads(body) if code == 200 else None), code, body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=5900)
    ap.add_argument("--token", default="")
    args = ap.parse_args()
    api = Api(f"http://{args.host}:{args.port}", args.token)

    print(f"\n=== DevvioRD file transfer test -> {args.host}:{args.port} ===\n")

    # --- listing ---
    code, body = api.get_json("/api/files?dir=")
    if code == 200 and body is not None and "entries" in body:
        ok("list root directory", f"{len(body['entries'])} entries, root={body.get('root')}")
    else:
        bad("list root directory", f"HTTP {code} {str(body)[:100]}")
        return 1

    # --- auth enforcement (only meaningful when a token is configured) ---
    if args.token:
        anon = Api(f"http://{args.host}:{args.port}", "")
        code, _ = anon.get_json("/api/files?dir=")
        if code == 401:
            ok("unauthenticated request rejected with 401")
        else:
            bad("unauthenticated request rejected with 401", f"got {code}")
        code, _ = anon.get_json("/api/files?dir=&token=wrong")
        if code == 401:
            ok("wrong token rejected")
        else:
            bad("wrong token rejected", f"got {code}")

    # --- small text file (phone -> server) ---
    payload = "hello from the phone\nسلام دنیا\nline3\n".encode("utf-8")
    entry, code, raw = api.upload("note.txt", payload)
    if entry:
        ok("upload small file", f"{entry['name']} ({entry['size']} bytes)")
    else:
        bad("upload small file", f"HTTP {code} {raw[:120]}")
        return 1

    # --- verify content by downloading it back ---
    code, data = api._req("/api/download?path=" + urllib.parse.quote(entry["path"]), method="GET")
    if code == 200 and data == payload:
        ok("download returns identical bytes", f"sha256 match, {len(data)} bytes")
    else:
        bad("download returns identical bytes", f"HTTP {code}, {len(data)} bytes")

    # --- binary file with a null byte and 3 MiB (multi-chunk) ---
    blob = os.urandom(3 * 1024 * 1024 + 137)
    entry2, code, raw = api.upload("payload.bin", blob)
    if entry2 and entry2["size"] == len(blob):
        ok("upload 3 MiB binary in chunks", f"{entry2['name']} {entry2['size']} bytes")
        code, data = api._req("/api/download?path=" + urllib.parse.quote(entry2["path"]), method="GET")
        same = hashlib.sha256(data).hexdigest() == hashlib.sha256(blob).hexdigest()
        if code == 200 and same:
            ok("multi-chunk upload integrity", "sha256 of download matches source")
        else:
            bad("multi-chunk upload integrity", f"HTTP {code}")
    else:
        bad("upload 3 MiB binary in chunks", f"HTTP {code} {raw[:120]}")

    # --- unicode filename ---
    entry3, code, raw = api.upload("گزارش نهایی ۱۴۰۳.txt", "محتوای فارسی".encode("utf-8"))
    if entry3:
        ok("unicode filename accepted", entry3["name"])
    else:
        bad("unicode filename accepted", f"HTTP {code}")

    # --- empty file edge case ---
    entry4, code, raw = api.upload("empty.dat", b"")
    if entry4 and entry4["size"] == 0:
        ok("zero-byte file")
    else:
        bad("zero-byte file", f"HTTP {code} {raw[:80]}")

    # --- folder + nested upload ---
    code, _ = api.post_json("/api/mkdir", {"dir": "", "name": "docs"})
    if code == 200:
        ok("create directory")
    else:
        bad("create directory", f"HTTP {code}")
    nested, code, _ = api.upload("inside.txt", b"nested", d="docs")
    if nested and nested["path"].startswith("docs/"):
        ok("upload into subdirectory", nested["path"])
    else:
        bad("upload into subdirectory", str(nested))

    # --- rename ---
    code, _ = api.post_json("/api/rename", {"path": entry["path"], "newName": "renamed.txt"})
    code2, listing = api.get_json("/api/files?dir=")
    names = [e["name"] for e in listing["entries"]] if listing else []
    if code == 200 and "renamed.txt" in names:
        ok("rename file")
    else:
        bad("rename file", f"HTTP {code}, names={names[:6]}")

    # --- delete ---
    code, _ = api.post_json("/api/delete", {"path": "renamed.txt"})
    code2, listing = api.get_json("/api/files?dir=")
    names = [e["name"] for e in listing["entries"]] if listing else []
    if code == 200 and "renamed.txt" not in names:
        ok("delete file")
    else:
        bad("delete file", f"HTTP {code}")

    # --- path traversal must be refused ---
    for evil in ["../../etc/passwd", "/etc/passwd", "..%2f..%2fetc%2fpasswd", "docs/../../.."]:
        code, _ = api.get_json("/api/files?dir=" + urllib.parse.quote(evil))
        code2, _ = api._req("/api/download?path=" + urllib.parse.quote(evil), method="GET")
        if code in (400, 404) and code2 in (400, 403, 404):
            ok(f"path traversal blocked: {evil}", f"list={code} download={code2}")
        else:
            bad(f"path traversal blocked: {evil}", f"list={code} download={code2}")

    # --- upload with a traversal filename is neutralised ---
    # Start from a clean slate: a reused server dir would otherwise make the
    # server de-duplicate to "evil (2).txt" and the assertion would misfire.
    for stale in ("evil.txt", "evil (2).txt"):
        api.post_json("/api/delete", {"path": stale})
    evil_entry, code, _ = api.upload("../../evil.txt", b"x")
    if (evil_entry and ".." not in evil_entry["path"]
            and evil_entry["path"].split(" (")[0].rstrip(")") in ("evil.txt", "evil")):
        ok("traversal filename sanitised", evil_entry["path"])
    else:
        bad("traversal filename sanitised", str(evil_entry))

    # --- oversized chunk rejected ---
    code, body = api.post_json("/api/upload/begin", {"name": "big.bin", "size": 10})
    if code == 200:
        up_id = json.loads(body)["id"]
        code, _ = api._req(f"/api/upload/chunk?id={up_id}&offset=0", data=b"x" * (5 << 20),
                           method="POST", ctype="application/octet-stream")
        if code in (400, 413):
            ok("oversized chunk rejected", f"HTTP {code}")
        else:
            bad("oversized chunk rejected", f"got {code}")
        # offset mismatch must be caught (resume safety)
        code, _ = api._req(f"/api/upload/chunk?id={up_id}&offset=999999", data=b"abc",
                           method="POST", ctype="application/octet-stream")
        if code == 409:
            ok("chunk offset mismatch rejected (resume safety)")
        else:
            bad("chunk offset mismatch rejected", f"got {code}")

    print("\n--- summary ---")
    passed = sum(1 for r in results if r)
    failed = len(results) - passed
    print(f"{passed} passed, {failed} failed")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
