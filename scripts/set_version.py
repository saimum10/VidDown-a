#!/usr/bin/env python3
"""Change the app version in version.properties (the only place the version is kept).

Usage:
  python3 scripts/set_version.py                 show the current version
  python3 scripts/set_version.py 0.1.0           set VERSION_NAME to 0.1.0 and raise VERSION_CODE by 1
  python3 scripts/set_version.py 0.1.0 --code 7  set both explicitly
  python3 scripts/set_version.py --bump          keep the name, raise VERSION_CODE by 1

VERSION_CODE must go up with every build that is installed over an older one; this script never lowers it unless --code says so.
"""
import argparse
import re
import sys
from pathlib import Path

FILE = Path(__file__).resolve().parent.parent / "version.properties"
NAME_OK = re.compile(r"^[0-9A-Za-z][0-9A-Za-z.+_-]*$")


def read() -> tuple:
    text = FILE.read_text(encoding="utf-8")
    name = re.search(r"^VERSION_NAME=(.*)$", text, re.M)
    code = re.search(r"^VERSION_CODE=(.*)$", text, re.M)
    if not name or not code:
        sys.exit(f"{FILE} must contain VERSION_NAME=... and VERSION_CODE=...")
    return text, name.group(1).strip(), int(code.group(1).strip())


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("name", nargs="?", help="new VERSION_NAME, for example 0.1.0 (a leading v is removed)")
    ap.add_argument("--code", type=int, help="new VERSION_CODE (default: the current one + 1)")
    ap.add_argument("--bump", action="store_true", help="only raise VERSION_CODE by 1")
    args = ap.parse_args()

    text, old_name, old_code = read()
    if args.name is None and not args.bump and args.code is None:
        print(f"VERSION_NAME={old_name}\nVERSION_CODE={old_code}")
        return

    new_name = old_name
    if args.name is not None:
        new_name = args.name[1:] if args.name[:1] in ("v", "V") else args.name
        if not NAME_OK.match(new_name):
            sys.exit(f"Not a usable version name: {args.name!r} (letters, digits and . + _ - only)")
    new_code = args.code if args.code is not None else old_code + 1
    if new_code < 1:
        sys.exit("VERSION_CODE must be 1 or higher")
    if new_code <= old_code:
        print(f"Warning: VERSION_CODE goes from {old_code} to {new_code}; Android will not update an installed app to a lower or equal code.")

    text = re.sub(r"^VERSION_NAME=.*$", lambda m: f"VERSION_NAME={new_name}", text, flags=re.M)
    text = re.sub(r"^VERSION_CODE=.*$", lambda m: f"VERSION_CODE={new_code}", text, flags=re.M)
    FILE.write_text(text, encoding="utf-8")
    print(f"VERSION_NAME: {old_name} -> {new_name}\nVERSION_CODE: {old_code} -> {new_code}")


if __name__ == "__main__":
    main()
