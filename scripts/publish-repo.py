#!/usr/bin/env python3
"""Build the extension store from one release build.

Reads what Gradle wrote (`build/source-info.properties`), the release APK, the JVM jar and the signing
certificate's fingerprint, and writes every index format a Mihon-family reader looks for:

  index.min.json   legacy list -- what Tachimanga's docs tell users to paste, and what Mihon uses to find
                   `repo.json` when given a legacy URL
  repo.json        legacy repo descriptor pointing at `index_v2` (the protobuf) and carrying the fingerprint
  index.json       the v2 index as JSON (Mihon sniffs the first byte: `{` = JSON, anything else = protobuf)
  index.pb         the v2 index as gzipped protobuf -- Mihon's preferred form, gzip is decompressed on read
  apk/<apk>        the APK, under the folder name the legacy loader hard-codes
  jar/<jar>        the JVM build for Suwayomi (`resources.jarUrl`, proto field 501)
  icon/<pkg>.png   the icon, under the other hard-coded folder

The protobuf is hand-encoded: the schema is five messages, and a script with no dependencies is one that
runs anywhere, including the CI runner. Field numbers come from Mihon's `NetworkExtensionStore` (and
Suwayomi's copy of it, which adds `jarUrl` = 501); `scripts/index.proto` is the same schema in .proto form.

Usage: publish-repo.py --apk <apk> --jar <jar> --icon <png> --info build/source-info.properties \
                       --fingerprint <sha256 hex> --base-url <raw url of the repo branch> --out <dir>
"""
import argparse
import gzip
import json
import os
import shutil

# ---- a minimal protobuf writer -------------------------------------------------------------------------


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def field_varint(num: int, value: int) -> bytes:
    return varint((num << 3) | 0) + varint(value)


def field_bytes(num: int, value: bytes) -> bytes:
    return varint((num << 3) | 2) + varint(len(value)) + value


def field_str(num: int, value: str) -> bytes:
    return field_bytes(num, value.encode("utf-8"))


# ---- the index ---------------------------------------------------------------------------------------------

CONTENT_WARNING_SAFE = 1  # ContentWarning enum: 0 unspecified, 1 safe, 2 mixed, 3 nsfw


def read_properties(path: str) -> dict:
    props = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def build(args):
    info = read_properties(args.info)
    pkg = info["packageName"]
    apk_name = info["apk"]
    jar_name = os.path.splitext(apk_name)[0] + ".jar"
    base = args.base_url.rstrip("/")
    source_id = int(info["sourceId"])
    version_code = int(info["versionCode"])

    apk_url = f"{base}/apk/{apk_name}"
    jar_url = f"{base}/jar/{jar_name}"
    icon_url = f"{base}/icon/{pkg}.png"

    # what the store is
    name, badge, website = "Uchiyomi", "UCHI", "https://uchiyomi.com"

    # ---- the v2 index, once as a dict (for JSON) ...
    source = {"id": str(source_id), "name": info["name"], "language": info["lang"], "homeUrl": website}
    extension = {
        "name": info["name"],
        "packageName": pkg,
        "resources": {"apkUrl": apk_url, "iconUrl": icon_url, "jarUrl": jar_url},
        "extensionLib": info["extLib"],
        "versionCode": str(version_code),
        "versionName": info["versionName"],
        "contentWarning": "CONTENT_WARNING_SAFE",
        "sources": [source],
    }
    index = {
        "name": name,
        "badgeLabel": badge,
        "signingKey": args.fingerprint,
        "contact": {"website": website, "discord": None},
        "extensionList": {"extensions": [extension]},
    }

    # ... and once as protobuf, field for field
    pb_source = field_varint(1, source_id) + field_str(2, info["name"]) + field_str(3, info["lang"]) + field_str(4, website)
    pb_resources = field_str(1, apk_url) + field_str(2, icon_url) + field_str(501, jar_url)
    pb_extension = (
        field_str(1, info["name"])
        + field_str(2, pkg)
        + field_bytes(3, pb_resources)
        + field_str(4, info["extLib"])
        + field_varint(5, version_code)
        + field_str(6, info["versionName"])
        + field_varint(7, CONTENT_WARNING_SAFE)
        + field_bytes(8, pb_source)
    )
    pb_index = (
        field_str(1, name)
        + field_str(2, badge)
        + field_str(3, args.fingerprint)
        + field_bytes(4, field_str(1, website))
        + field_bytes(101, field_bytes(1, pb_extension))
    )

    # ---- legacy
    legacy = [{
        "name": f"Tachiyomi: {info['name']}",
        "pkg": pkg,
        "apk": apk_name,
        "lang": info["lang"],
        "code": version_code,
        "version": info["versionName"],
        "nsfw": 0,
        "sources": [{"id": str(source_id), "lang": info["lang"], "name": info["name"], "baseUrl": website}],
    }]
    repo = {
        "index_v2": f"{base}/index.pb",
        "meta": {"name": name, "shortName": badge, "website": website, "signingKeyFingerprint": args.fingerprint},
    }

    # ---- write
    out = args.out
    for d in ("apk", "jar", "icon"):
        os.makedirs(os.path.join(out, d), exist_ok=True)
    shutil.copyfile(args.apk, os.path.join(out, "apk", apk_name))
    shutil.copyfile(args.jar, os.path.join(out, "jar", jar_name))
    shutil.copyfile(args.icon, os.path.join(out, "icon", f"{pkg}.png"))
    with open(os.path.join(out, "index.min.json"), "w", encoding="utf-8") as f:
        json.dump(legacy, f, separators=(",", ":"))
    with open(os.path.join(out, "repo.json"), "w", encoding="utf-8") as f:
        json.dump(repo, f, indent=2)
        f.write("\n")
    with open(os.path.join(out, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, indent=2)
        f.write("\n")
    with open(os.path.join(out, "index.pb"), "wb") as f:
        f.write(gzip.compress(pb_index, mtime=0))
    print(f"store written to {out}: {apk_name}, {jar_name}, id {source_id}, fingerprint {args.fingerprint[:16]}…")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--apk", required=True)
    p.add_argument("--jar", required=True)
    p.add_argument("--icon", required=True)
    p.add_argument("--info", required=True)
    p.add_argument("--fingerprint", required=True, help="SHA-256 of the signing certificate, lowercase hex, no colons")
    p.add_argument("--base-url", required=True, help="where the repo branch is served from, e.g. https://raw.githubusercontent.com/AngeloSha/uchiyomi-extension/repo")
    p.add_argument("--out", required=True)
    args = p.parse_args()
    fp = args.fingerprint.lower().replace(":", "")
    if len(fp) != 64 or any(c not in "0123456789abcdef" for c in fp):
        raise SystemExit("--fingerprint must be a 64-hex-digit SHA-256")
    args.fingerprint = fp
    build(args)


if __name__ == "__main__":
    main()
