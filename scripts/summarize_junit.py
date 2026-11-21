#!/usr/bin/env python3
"""Summarise JUnit XML results as Markdown and FAIL when there is nothing to summarise or anything failed.

Used by .github/workflows/poc.yml so that an emulator run that executed zero tests can never look green.
Usage: summarize_junit.py <directory> [<directory> ...] [--title TEXT]
"""
import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def summarize(roots, title: str):
    roots = [roots] if isinstance(roots, Path) else list(roots)
    files = sorted(f for r in roots for f in r.rglob("TEST-*.xml"))
    lines = [f"### {title}", ""]
    total = failed = skipped = 0
    for f in files:
        suite = ET.parse(f).getroot()
        suites = [suite] if suite.tag == "testsuite" else suite.findall("testsuite")
        for s in suites:
            for case in s.findall("testcase"):
                total += 1
                name = f"{case.get('classname', '')}.{case.get('name', '')}"
                if case.find("failure") is not None or case.find("error") is not None:
                    failed += 1
                    msg = (case.find("failure") if case.find("failure") is not None else case.find("error")).get("message", "")
                    lines.append(f"- FAIL `{name}`: {msg[:300]}")
                elif case.find("skipped") is not None:
                    skipped += 1
                    lines.append(f"- SKIPPED `{name}`")
                else:
                    lines.append(f"- PASS `{name}`")
    lines += ["", f"{total} tests, {failed} failed, {skipped} skipped."]
    ok = total > 0 and failed == 0 and skipped == 0
    if total == 0:
        lines.append("NO TEST RESULTS FOUND: treated as failure.")
    return "\n".join(lines), ok


def main(argv=None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("directories", nargs="+")
    p.add_argument("--title", default="Instrumented test results")
    args = p.parse_args(argv)
    text, ok = summarize([Path(d) for d in args.directories], args.title)
    print(text)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
