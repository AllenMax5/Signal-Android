#!/usr/bin/env python3
"""Inspect, stop and export the auto-started Signal Android Debug E2EE trace using ADB."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

LABEL = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
FILE = re.compile(r"[0-9]+_[0-9a-f-]{36}-(?:[0-9]{4,}|native)\.jsonl\Z")


def label(value):
    if not LABEL.fullmatch(value):
        raise argparse.ArgumentTypeError("Use 1-64 ASCII letters, digits, underscores or hyphens")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit emulator/device serial")
    parser.add_argument("--package", required=True, help="Installed Debug application ID")
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb"))
    commands = parser.add_subparsers(dest="command", required=True)
    start = commands.add_parser("start")
    start.add_argument("--run-id", type=label, required=True)
    start.add_argument("--device", type=label, required=True)
    start.add_argument("--step", type=label, default="unassigned")
    step = commands.add_parser("step")
    step.add_argument("--step", type=label, required=True)
    commands.add_parser("stop")
    commands.add_parser("status")
    export = commands.add_parser("export")
    export.add_argument("--output", type=Path, required=True)
    export.add_argument("--prefix", help="Capture prefix returned by start; omit to export all retained captures")
    export.add_argument("--run-id", type=label, help="Export every process segment belonging to one runId")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.package):
        parser.error("Invalid application ID")
    adb = [args.adb, "-s", args.serial]

    def run(parts):
        return subprocess.run(adb + parts, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

    if args.command == "export":
        if args.prefix and not re.fullmatch(r"[0-9]+_[0-9a-f-]{36}", args.prefix):
            parser.error("Invalid capture prefix")
        names = run(["shell", "run-as", args.package, "ls", "no_backup/e2ee-trace"]).decode().splitlines()
        selected = [name.strip() for name in names if FILE.fullmatch(name.strip()) and (not args.prefix or name.startswith(args.prefix + "-"))]
        if args.run_id:
            # 按文件首条事件的 runId 筛选，确保进程重启后所有分段仍能一次导出。
            selected = [name for name in selected if json.loads(run(["shell", "run-as", args.package, "head", "-n", "1", "no_backup/e2ee-trace/" + name]).decode()).get("runId") == args.run_id]
        if not selected:
            raise RuntimeError("No matching capture files; check package, prefix and capture status")
        args.output.mkdir(parents=True, exist_ok=True)
        for name in sorted(selected):
            data = run(["exec-out", "run-as", args.package, "cat", "no_backup/e2ee-trace/" + name])
            target = args.output / name
            # A second export must not silently replace previously collected evidence.
            with target.open("xb") as handle:
                handle.write(data)
            print(target)
        return
    extras = {"command": args.command}
    if args.command == "start":
        extras.update(runId=args.run_id, device=args.device, stepId=args.step)
    elif args.command == "step":
        extras["stepId"] = args.step
    call = ["shell", "am", "broadcast", "-n", args.package + "/org.thoughtcrime.securesms.debugtrace.TraceControlReceiver"]
    for key, value in extras.items():
        call.extend(["--es", key, value])
    result = run(call).decode()
    print(result, end="")
    if not re.search(r"Broadcast completed: result=-1(?:,|\s|$)", result):
        raise RuntimeError("Debug trace command was not acknowledged; verify Debug build, running app and shell permissions")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stderr.decode(errors="replace"), file=sys.stderr)
        sys.exit(1)
