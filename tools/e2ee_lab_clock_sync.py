#!/usr/bin/env python3
"""Record device-to-host clock offsets for cross-device trace ordering."""

import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", action="append", required=True, help="ROLE=ADB_SERIAL")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default=str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
    parser.add_argument("--interval", type=float, default=30)
    parser.add_argument("--duration", type=float, default=21600)
    args = parser.parse_args()
    if args.interval < 1 or args.duration < 1:
        parser.error("interval and duration must be positive")
    devices = [item.split("=", 1) for item in args.device]
    if any(len(item) != 2 or not all(item) for item in devices):
        parser.error("--device must use ROLE=ADB_SERIAL")

    os.umask(0o077)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    stopped = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    deadline = time.monotonic() + args.duration

    with args.output.open("a", encoding="utf-8") as target:
        while not stopped.is_set() and time.monotonic() < deadline:
            for role, serial in devices:
                host_before_ms = time.time_ns() // 1_000_000
                try:
                    result = subprocess.run(
                        [args.adb, "-s", serial, "shell", "date", "+%s%3N"],
                        check=True, capture_output=True, text=True, timeout=10
                    )
                    host_after_ms = time.time_ns() // 1_000_000
                    device_ms = int(result.stdout.strip())
                    entry = {
                        "role": role, "serial": serial, "hostBeforeMs": host_before_ms,
                        "hostAfterMs": host_after_ms, "deviceMs": device_ms,
                        "offsetMs": (host_before_ms + host_after_ms) // 2 - device_ms,
                        "uncertaintyMs": host_after_ms - host_before_ms,
                    }
                except (OSError, subprocess.SubprocessError, ValueError) as error:
                    entry = {"role": role, "serial": serial, "hostBeforeMs": host_before_ms,
                             "error": type(error).__name__, "detail": str(error)}
                target.write(json.dumps(entry, separators=(",", ":")) + "\n")
                target.flush()
            stopped.wait(args.interval)


if __name__ == "__main__":
    main()
