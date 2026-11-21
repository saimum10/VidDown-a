#!/usr/bin/env python3
"""Keep the legal documents shown in the app's Licenses screen identical to the repository's files.

  python3 scripts/sync_legal_assets.py          copy the files into app/src/main/assets/legal/
  python3 scripts/sync_legal_assets.py --check  exit 1 when the copies are stale (used by a test)
"""
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAIRS = {
    "LICENSE": "LICENSE.txt",
    "THIRD_PARTY_NOTICES.md": "THIRD_PARTY_NOTICES.md",
    "docs/dependencies.md": "dependencies.md",
}
DEST = ROOT / "app/src/main/assets/legal"


def stale() -> list:
    return [dst for src, dst in PAIRS.items()
            if not (DEST / dst).is_file() or (DEST / dst).read_bytes() != (ROOT / src).read_bytes()]


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if "--check" in argv:
        bad = stale()
        if bad:
            print(f"stale legal assets: {bad}; run python3 scripts/sync_legal_assets.py", file=sys.stderr)
            return 1
        return 0
    DEST.mkdir(parents=True, exist_ok=True)
    for src, dst in PAIRS.items():
        shutil.copyfile(ROOT / src, DEST / dst)
    print("synced", ", ".join(PAIRS.values()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
