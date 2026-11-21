#!/usr/bin/env python3
"""Print the ABIs that may be shipped: those whose runtime verification in native/bundle.lock.json starts with "PASS".

Used by the release workflow (-PverifiedAbis=...). An ABI with "NOT VERIFIED" is never printed, so an unverified ABI cannot
reach an APK by accident. Output: comma separated list on stdout (empty line if none). Exit code 0 always, unless the manifest is unreadable.
"""
import json
import sys
from pathlib import Path


def verified_abis(lock: dict) -> list:
    out = []
    for abi, entry in sorted(lock.get("abis", {}).items()):
        status = entry.get("verification", {}).get("runtime_on_device_or_emulator", "")
        if isinstance(status, str) and status.startswith("PASS"):
            out.append(abi)
    return out


def main(argv=None) -> int:
    path = Path((argv or sys.argv[1:] or ["native/bundle.lock.json"])[0])
    try:
        lock = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        print(f"ERROR: cannot read {path}: {e}", file=sys.stderr)
        return 2
    print(",".join(verified_abis(lock)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
