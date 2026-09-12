"""Run a spoken-command transcript through Sonju and retain its actual outcome.

This exercises the executor after speech recognition, not microphone accuracy.
Use public, reversible tasks; never pass credentials or a real send/payment request.
"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--command", required=True)
    parser.add_argument("--package")
    parser.add_argument("--text", action="append", default=[])
    parser.add_argument("--checked", action="append", default=[], metavar="RESOURCE_ID=true|false")
    parser.add_argument("--timeout", type=int, default=200)
    parser.add_argument("--no-model", action="store_true", help="Require a completed run with zero AI calls")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", args.name):
        parser.error("name must contain only letters, digits, underscores or hyphens")
    states = [value.rsplit("=", 1) for value in args.checked]
    if any(len(value) != 2 or value[1] not in ("true", "false") for value in states):
        parser.error("checked must be RESOURCE_ID=true or RESOURCE_ID=false")
    output = Path(__file__).resolve().parents[1] / "app/build/reports/completion-qa" / args.name
    output.mkdir(parents=True, exist_ok=True)
    adb = ["adb", "-s", args.serial]

    def shell(*argv):
        return subprocess.run(adb + ["shell", shlex.join(argv)], capture_output=True,
                              text=True, encoding="utf-8", errors="replace", check=True).stdout

    lines = []
    terminal = threading.Event()
    collector = subprocess.Popen(adb + ["logcat", "-T", "1", "-v", "threadtime", "SonjuFlow:D", "*:S"],
                                 stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                 text=True, encoding="utf-8", errors="replace")

    def collect():
        active = False
        with (output / "flow.log").open("w", encoding="utf-8") as log:
            for line in collector.stdout:
                log.write(line)
                log.flush()
                if "command received direct=" in line:
                    active = True
                if not active:
                    continue
                lines.append(line)
                if any(marker in line for marker in ("goal verified source=", "local postcondition verified source=",
                                                     "session stopped tools=", "user stop requested", "command failed source=")):
                    terminal.set()

    reader = threading.Thread(target=collect, daemon=True)
    reader.start()
    started = time.monotonic()
    try:
        launch = shell("am", "start", "--activity-single-top", "-n", "com.hwanghj09.sonju/.MainActivity", "--es",
                       "com.hwanghj09.sonju.extra.VOICE_COMMAND", args.command)
        print(launch.strip(), flush=True)
        while not terminal.wait(15):
            recent = [line.strip() for line in lines if any(marker in line for marker in
                      ("model request", "verification=", "execution result", "session stopped"))]
            print(f"elapsed={int(time.monotonic() - started)}s " + (recent[-1] if recent else "waiting for executor"),
                  flush=True)
            if time.monotonic() - started >= args.timeout:
                shell("am", "start", "--activity-single-top", "-n", "com.hwanghj09.sonju/.MainActivity", "--es",
                      "com.hwanghj09.sonju.extra.VOICE_COMMAND", "멈춰")
                terminal.wait(5)
                break
        elapsed = round(time.monotonic() - started, 1)
        # Observe through the shell-protected debug receiver. UiAutomator reconnects accessibility
        # and cannot become idle on a running timer; its old output file is not fresh evidence.
        if terminal.is_set():
            time.sleep(1)
        shell("run-as", "com.hwanghj09.sonju", "rm", "-f", "cache/command-qa-snapshot.xml")
        shell("am", "broadcast", "-n", "com.hwanghj09.sonju/.accessibility.DebugSnapshotReceiver")
        xml = shell("run-as", "com.hwanghj09.sonju", "cat", "cache/command-qa-snapshot.xml")
        (output / "screen.xml").write_text(xml, encoding="utf-8")
        nodes = list(ET.fromstring(xml).iter("node"))
        external = [node for node in nodes if node.get("package") != "com.hwanghj09.sonju"]
        labels = [value for node in external for key in ("text", "content-desc")
                  if (value := node.get(key))]
        goals = [line for line in lines if "goal verified source=" in line]
        local = [line for line in lines if "local postcondition verified source=" in line]
        verified = goals or local
        metric = re.search(r"source=(\w+) tools=(\d+) modelCalls=(\d+)", verified[-1]) if verified else None
        package_ok = not args.package or any(node.get("package") == args.package for node in external)
        text_ok = all(any(expected in actual for actual in labels) for expected in args.text)
        state_ok = all(len(matches := [node for node in external if node.get("resource-id") == resource]) == 1
                       and matches[0].get("checkable") == "true" and matches[0].get("checked") == expected
                       for resource, expected in states)
        result = dict(command=args.command, serial=args.serial, elapsedSeconds=elapsed,
                      goalVerified=bool(goals), localPostconditionVerified=bool(local),
                      packageMatches=package_ok, expectedTextMatches=text_ok,
                      expectedStateMatches=state_ok,
                      source=metric[1] if metric else None, tools=int(metric[2]) if metric else None,
                      modelCalls=int(metric[3]) if metric else None,
                      passed=bool(verified) and package_ok and text_ok and state_ok and
                      (not args.no_model or metric is not None and metric[3] == "0"))
        (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False), flush=True)
        if not result["passed"]:
            print("Observed labels: " + " | ".join(labels[:35]), flush=True)
        return 0 if result["passed"] else 1
    finally:
        collector.terminate()
        collector.wait(timeout=10)
        reader.join(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
