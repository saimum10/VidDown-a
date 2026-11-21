#!/usr/bin/env python3
"""Zip every Gradle test-result and report directory of this repository into one file for a release.

Only directories named test-results or reports that sit directly under a module's build/ folder are taken
(third_party and the .gradle cache are skipped). It FAILS when nothing is found, so a release can never
claim test results it does not have. Usage: collect_reports.py --out dist/test-and-lint-reports.zip [--root .]
"""
import argparse
import sys
import zipfile
from pathlib import Path

SKIP_TOP = {"third_party", ".git", ".gradle", ".cache"}
WANTED = {"test-results", "reports"}


def find_dirs(root: Path):
    found = []
    for build in root.glob("*/build"):
        if build.parent.name in SKIP_TOP or not build.is_dir():
            continue
        for name in WANTED:
            d = build / name
            if d.is_dir():
                found.append(d)
    return sorted(found)


def collect(root: Path, out: Path) -> int:
    dirs = find_dirs(root)
    count = 0
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for d in dirs:
            for f in sorted(p for p in d.rglob("*") if p.is_file()):
                z.write(f, f.relative_to(root).as_posix())
                count += 1
    return count


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    n = collect(Path(a.root), Path(a.out))
    if n == 0:
        Path(a.out).unlink(missing_ok=True)
        print("ERROR: no test or lint reports found; refusing to publish an empty results file", file=sys.stderr)
        return 1
    print(f"OK: {n} report files written to {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
