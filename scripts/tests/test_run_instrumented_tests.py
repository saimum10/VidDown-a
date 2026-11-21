"""Tests for scripts/run_instrumented_tests.sh with a fake `adb` and a fake `gradlew` (no emulator needed).

The fake adb reads a JSON config. `devices` is a list of "snapshots": each `adb devices` call returns the next snapshot
(the last one repeats). A prop value may be a list, consumed one entry per getprop call (the last one repeats).
"""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "run_instrumented_tests.sh"

FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, sys
cfg_path = os.environ["FAKE_ADB_CONFIG"]
cfg = json.load(open(cfg_path))
state_path = cfg_path + ".state"
state = json.load(open(state_path)) if os.path.exists(state_path) else {}
args = sys.argv[1:]
with open(cfg_path + ".calls", "a") as f:
    f.write(" ".join(args) + "\n")

def nxt(key, seq):
    i = state.get(key, 0)
    state[key] = i + 1
    return seq[min(i, len(seq) - 1)]

def save():
    json.dump(state, open(state_path, "w"))

if args[:1] == ["devices"]:
    snap = nxt("devices", cfg["devices"])
    print("List of devices attached" if "-l" not in args else "List of devices attached")
    for serial, st in snap:
        print(f"{serial}\t{st}")
elif args[:1] == ["-s"] and args[2:3] == ["shell"] and args[3:4] == ["getprop"]:
    v = cfg["props"].get(args[4], "")
    if isinstance(v, list):
        v = nxt("prop:" + args[4], v)
    print(v)
elif args[:1] == ["-s"] and args[2:5] == ["shell", "pm", "path"]:
    seq = cfg.get("pm_ok", [True])
    ok = nxt("pm", seq)
    if ok:
        print("package:/system/framework/framework-res.apk")
    save()
    sys.exit(0 if ok else 1)
elif args[:1] == ["-s"] and "logcat" in args:
    print("fake logcat line")
save()
'''

FAKE_GRADLE = r'''#!/usr/bin/env bash
echo "$@" > "$FAKE_GRADLE_ARGS"
exit "${FAKE_GRADLE_EXIT:-0}"
'''


class GateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.adb = self.tmp / "adb"
        self.adb.write_text(FAKE_ADB)
        self.adb.chmod(0o755)
        self.gradle = self.tmp / "gradlew"
        self.gradle.write_text(FAKE_GRADLE)
        self.gradle.chmod(0o755)
        self.cfg = self.tmp / "adb.json"
        self.gradle_args = self.tmp / "gradle.args"
        self.diag = self.tmp / "out" / "diag.txt"

    def run_gate(self, props=None, devices=None, pm_ok=None, abi="x86", api="34", gradle_exit=0, env=None, args=("--stacktrace",)):
        base = {
            "ro.build.version.sdk": "34",
            "ro.product.cpu.abilist": "x86_64,x86",
            "sys.boot_completed": "1",
            "init.svc.bootanim": "stopped",
        }
        base.update(props or {})
        cfg = {"props": base, "devices": devices or [[["emulator-5554", "device"]]]}
        if pm_ok is not None:
            cfg["pm_ok"] = pm_ok
        self.cfg.write_text(json.dumps(cfg))
        e = dict(os.environ)
        e.update(
            ADB=str(self.adb),
            GRADLEW=str(self.gradle),
            FAKE_ADB_CONFIG=str(self.cfg),
            FAKE_GRADLE_ARGS=str(self.gradle_args),
            FAKE_GRADLE_EXIT=str(gradle_exit),
            DIAG_FILE=str(self.diag),
            READY_TIMEOUT="3",
            POLL_INTERVAL="0.05",
            OFFLINE_RECONNECT_AFTER="0",
        )
        e.update(env or {})
        p = subprocess.run(["bash", str(SCRIPT), abi, api, *args], capture_output=True, text=True, env=e, timeout=60)
        return p

    def adb_calls(self):
        f = Path(str(self.cfg) + ".calls")
        return f.read_text() if f.exists() else ""

    def test_ready_matching_device_runs_gradle_with_all_args(self):
        p = self.run_gate(args=("--stacktrace", "-PnativeAbis=x86"))
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(self.gradle_args.read_text().strip(), "--no-daemon connectedDebugAndroidTest --stacktrace -PnativeAbis=x86")

    def test_x86_apk_on_x86_64_image_with_32bit_support_is_accepted(self):
        p = self.run_gate(abi="x86", props={"ro.product.cpu.abilist": "x86_64,x86"})
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)

    def test_waits_through_offline_and_reconnects(self):
        devices = [[["emulator-5554", "offline"]], [["emulator-5554", "offline"]], [["emulator-5554", "device"]]]
        p = self.run_gate(devices=devices)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertIn("reconnect offline", self.adb_calls())

    def test_waits_for_boot_completed(self):
        p = self.run_gate(props={"sys.boot_completed": ["", "0", "1"]})
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)

    def test_waits_for_package_manager(self):
        p = self.run_gate(pm_ok=[False, False, True])
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)

    def test_waits_for_boot_animation_to_stop(self):
        p = self.run_gate(props={"init.svc.bootanim": ["running", "stopped"]})
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)

    def test_device_that_stays_offline_fails_before_gradle(self):
        p = self.run_gate(devices=[[["emulator-5554", "offline"]]], env={"READY_TIMEOUT": "1"})
        self.assertEqual(p.returncode, 1)
        self.assertFalse(self.gradle_args.exists(), "Gradle must not start on an offline device")
        self.assertIn("emulator-5554 is in adb state 'offline'", p.stdout)
        self.assertTrue(self.diag.exists())

    def test_no_device_fails_before_gradle(self):
        p = self.run_gate(devices=[[]], env={"READY_TIMEOUT": "1"})
        self.assertEqual(p.returncode, 1)
        self.assertFalse(self.gradle_args.exists())
        self.assertIn("no device listed by adb", p.stdout)

    def test_never_booted_fails_before_gradle(self):
        p = self.run_gate(props={"sys.boot_completed": "0"}, env={"READY_TIMEOUT": "1"})
        self.assertEqual(p.returncode, 1)
        self.assertFalse(self.gradle_args.exists())
        self.assertIn("sys.boot_completed='0'", p.stdout)

    def test_missing_abi_is_named_explicitly(self):
        p = self.run_gate(abi="x86", props={"ro.product.cpu.abilist": "x86_64"})
        self.assertEqual(p.returncode, 1)
        self.assertFalse(self.gradle_args.exists())
        self.assertIn("[x86_64]", p.stdout)
        self.assertIn("'x86'", p.stdout)
        self.assertIn("incompatible", p.stdout)

    def test_abi_must_be_a_whole_list_entry_not_a_substring(self):
        # "x86" is a substring of "x86_64": must not count as supported.
        p = self.run_gate(abi="x86", props={"ro.product.cpu.abilist": "x86_64"})
        self.assertEqual(p.returncode, 1)

    def test_arm_abi_on_x86_image_is_rejected(self):
        p = self.run_gate(abi="arm64-v8a")
        self.assertEqual(p.returncode, 1)
        self.assertFalse(self.gradle_args.exists())

    def test_api_below_min_sdk_is_rejected(self):
        p = self.run_gate(props={"ro.build.version.sdk": "23"}, api="23")
        self.assertEqual(p.returncode, 1)
        self.assertIn("below minSdk", p.stdout)

    def test_api_must_match_matrix_cell(self):
        p = self.run_gate(props={"ro.build.version.sdk": "30"}, api="34")
        self.assertEqual(p.returncode, 1)
        self.assertIn("expects 34", p.stdout)

    def test_two_devices_are_rejected(self):
        p = self.run_gate(devices=[[["emulator-5554", "device"], ["emulator-5556", "device"]]])
        self.assertEqual(p.returncode, 1)
        self.assertFalse(self.gradle_args.exists())
        self.assertIn("Expected exactly one emulator", p.stdout)

    def test_gradle_failure_is_propagated_and_device_state_dumped(self):
        p = self.run_gate(gradle_exit=3)
        self.assertEqual(p.returncode, 3)
        diag = self.diag.read_text()
        self.assertIn("ro.product.cpu.abilist=x86_64,x86", diag)
        self.assertIn("ro.build.version.sdk=34", diag)
        self.assertIn("emulator-5554", diag)

    def test_usage_error(self):
        p = subprocess.run(["bash", str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(p.returncode, 2)


if __name__ == "__main__":
    unittest.main()
