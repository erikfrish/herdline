#!/usr/bin/env python3
"""Run one changed-feature check, never the full regression suite."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
PROFILES = {
    "header": (["HeaderFitTest", "NewTabTargetTest"], ["ui.session.TabsHeaderTest"]),
    "pin": (["DoneCardsTest", "LiveCardTest"], ["service.AgentMonitorPersistenceTest", "e2e.PinSheetTest"]),
    "notifications": (["LiveFieldsTest", "DoneCardsTest"], []),
    "receipts": (["HandedReceiptTest", "SteerFlowTest", "ChatModelTest"], []),
    "forms": (["ChatFormsTest", "WaitingQuestionTest"], []),
    "terminal": (["OutputFocusTest", "LineEditTest"], ["ui.terminal.TerminalViewImeTest"]),
}
PACKAGE = "io.github.erikfrish.herdline.qa"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", choices=PROFILES)
    parser.add_argument("--ui", action="store_true", help="also run this area's isolated Compose/storage tests")
    parser.add_argument("--prepare-ui", action="store_true", help="build test APKs first; preparation is timed separately")
    parser.add_argument("--serial", help="explicit owned QA emulator transport")
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"), help="adb executable or owned isolated wrapper")
    parser.add_argument("--seconds", type=int, default=120, help="maximum total check time, excluding explicit APK preparation")
    args = parser.parse_args()
    units, ui = PROFILES[args.profile]
    if args.seconds <= 0:
        parser.error("--seconds must be positive")
    if args.prepare_ui and not args.ui:
        parser.error("--prepare-ui requires --ui")
    if args.ui and (not ui or not args.serial):
        parser.error("--ui needs a UI-enabled profile and an explicit --serial")
    if args.serial and args.serial not in {"emulator-5556", "emulator-5558", "localfilesystem:/bridge/guest.sock"}:
        parser.error("only explicitly assigned QA emulator transports are allowed")

    stamp = time.strftime("%Y%m%dT%H%M%S") + f"-{os.getpid()}"
    out = ROOT / "app/build/reports/fast-check" / stamp
    out.mkdir(parents=True)
    result = {"profile": args.profile, "ui": args.ui, "serial": args.serial, "steps": [], "status": "failed"}
    started = time.monotonic()
    deadline = started + args.seconds

    def run(name: str, command: list[str], *, prepare: bool = False, stdin: Path | None = None) -> str:
        begin = time.monotonic()
        timeout = 900 if prepare else max(0.01, deadline - begin)
        log = out / (name + ".log")
        print(f"[{name}]", flush=True)
        try:
            with log.open("w") as stream:
                if stdin is None:
                    process = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
                else:
                    with stdin.open("rb") as source:
                        process = subprocess.run(command, cwd=ROOT, stdin=source, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
            text = log.read_text()
            result["steps"].append({"name": name, "seconds": round(time.monotonic() - begin, 3), "exit": process.returncode})
            if process.returncode:
                raise RuntimeError(f"{name} failed; see {log}")
            return text
        except subprocess.TimeoutExpired:
            result["steps"].append({"name": name, "seconds": round(time.monotonic() - begin, 3), "timeout": True})
            raise RuntimeError(f"{name} exceeded the check deadline; see {log}") from None

    adb = [args.adb, "-s", args.serial] if args.ui else []
    try:
        if args.ui:
            avd = run("device", adb + ["shell", "getprop", "ro.boot.qemu.avd_name"]).strip()
            if not avd.startswith("qa"):
                raise RuntimeError(f"refusing non-QA emulator: {avd!r}")
            result["avd"] = avd
        if args.ui:
            run("prepare" if args.prepare_ui else "build-ui", ["./gradlew", "--offline", "-Pherdline.testBuildType=qa", ":app:assembleQa", ":app:assembleQaAndroidTest", "-x", "buildRust"], prepare=args.prepare_ui)
            if args.prepare_ui:
                result["preparation_seconds"] = round(time.monotonic() - started, 3)
                started = time.monotonic()
                deadline = started + args.seconds

        command = ["./gradlew", "--offline", ":app:testDebugUnitTest", "--rerun", "-x", "buildRust"]
        for owner in units:
            command += ["--tests", "*" + owner]
        run("unit", command)
        suites = []
        reports = ROOT / "app/build/test-results/testDebugUnitTest"
        for path in reports.glob("TEST-*.xml"):
            suite = ET.parse(path).getroot()
            if suite.attrib.get("name", "").split(".")[-1] in units:
                suites.append(suite)
        count = sum(int(s.attrib["tests"]) for s in suites)
        failures = sum(int(s.attrib.get(k, 0)) for s in suites for k in ("failures", "errors", "skipped"))
        if len(suites) != len(units) or not count or failures:
            raise RuntimeError("selected unit owners did not all execute successfully")
        result["unit_tests"] = count

        if args.ui:
            target = ROOT / "app/build/outputs/apk/qa/app-qa.apk"
            test = ROOT / "app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk"
            if not target.is_file() or not test.is_file():
                raise RuntimeError("QA APKs are absent; run with --prepare-ui first")
            run("install-target", adb + ["shell", "-T", "cmd", "package", "install", "-r", "-S", str(target.stat().st_size)], stdin=target)
            run("install-tests", adb + ["shell", "-T", "cmd", "package", "install", "-r", "-S", str(test.stat().st_size)], stdin=test)
            classes = ",".join("io.github.erikfrish.herdline." + name for name in ui)
            previous_keyboard = None
            keyboard_setting = ["shell", "settings"]
            try:
                if args.profile == "terminal":
                    previous_keyboard = run("keyboard-before", adb + keyboard_setting + ["get", "secure", "show_ime_with_hard_keyboard"]).strip()
                    if previous_keyboard not in {"0", "1", "null"}:
                        raise RuntimeError("unexpected emulator software-keyboard setting")
                    run("keyboard-enable", adb + keyboard_setting + ["put", "secure", "show_ime_with_hard_keyboard", "1"])
                text = run("instrumentation", adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", classes, RUNNER])
            finally:
                if previous_keyboard in {"0", "1", "null"}:
                    restore = ["delete", "secure", "show_ime_with_hard_keyboard"] if previous_keyboard == "null" else ["put", "secure", "show_ime_with_hard_keyboard", previous_keyboard]
                    subprocess.run(adb + keyboard_setting + restore, cwd=ROOT, check=True, capture_output=True, timeout=10)
                    result["keyboard_setting_restored"] = previous_keyboard
            match = re.search(r"^OK \((\d+) tests?\)", text, re.MULTILINE)
            if not match or int(match.group(1)) == 0 or "FAILURES!!!" in text or "INSTRUMENTATION_FAILED" in text:
                raise RuntimeError("instrumentation did not report a passing non-empty test run")
            result["ui_tests"] = int(match.group(1))
        result["status"] = "passed"
        return 0
    except (RuntimeError, OSError, ET.ParseError, subprocess.SubprocessError) as error:
        result["error"] = str(error)
        print(error, file=sys.stderr)
        return 1
    finally:
        result["check_seconds"] = round(time.monotonic() - started, 3)
        (out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result), flush=True)
        print(f"Report: {out}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
