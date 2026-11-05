#!/usr/bin/env python3
"""Continuously capture Signal foreground screens and network transitions for an E2EE lab run.

Start before reinstalling the test app; leave running through acts 01–08. The app's
private JSONL is captured independently by its debug auto-start provider.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import threading
import time


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ROLE = re.compile(r"[a-z][a-z0-9_-]{0,31}\Z")
PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\Z")


def adb_call(adb, serial, *parts, timeout=12):
    return subprocess.run([adb, "-s", serial, *parts], check=True, capture_output=True, timeout=timeout).stdout


def foreground_package(window_dump):
    text = window_dump.decode(errors="replace")
    for line in text.splitlines():
        if "mCurrentFocus=" in line or "mFocusedApp=" in line:
            match = re.search(r"([A-Za-z][A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+", line)
            if match:
                return match.group(1)
    return None


def active_default_network(connectivity_dump):
    match = re.search(r"^\s*Active default network:\s*(\S+)", connectivity_dump.decode(errors="replace"), re.MULTILINE)
    return match.group(1) if match else "unknown"


class Capture:
    def __init__(self, args):
        self.args = args
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.used_bytes = 0
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        os.chmod(self.output, 0o700)
        self.manifest = self.output / "screenshots.jsonl"

    def record(self, kind, role, serial, **data):
        item = {"kind": kind, "role": role, "serial": serial,
                "timestampMs": time.time_ns() // 1_000_000,
                "monotonicNs": time.monotonic_ns(), **data}
        with self.lock, self.manifest.open("a", encoding="utf-8") as target:
            target.write(json.dumps(item, ensure_ascii=False, separators=(",", ":")) + "\n")

    def watch_device(self, role, serial):
        previous_hash = None
        previous_route = None
        previous_default_network = None
        folder = self.output / "screenshots" / role
        folder.mkdir(parents=True)
        self.record("watch_started", role, serial)
        while not self.stop.is_set():
            started = time.monotonic()
            try:
                route = adb_call(self.args.adb, serial, "shell", "ip", "route", "get", "1.1.1.1").decode(errors="replace").strip()
            except (OSError, subprocess.SubprocessError) as error:
                route = f"unavailable:{type(error).__name__}"
            if route != previous_route:
                self.record("network_route", role, serial, route=route)
                previous_route = route
            try:
                default_network = active_default_network(adb_call(self.args.adb, serial, "shell", "dumpsys", "connectivity"))
            except (OSError, subprocess.SubprocessError) as error:
                default_network = f"unavailable:{type(error).__name__}"
            if default_network != previous_default_network:
                self.record("default_network", role, serial, network=default_network)
                previous_default_network = default_network

            try:
                focus = foreground_package(adb_call(self.args.adb, serial, "shell", "dumpsys", "window"))
                if focus == self.args.package:
                    png = adb_call(self.args.adb, serial, "exec-out", "screencap", "-p", timeout=20)
                    if not png.startswith(PNG_SIGNATURE):
                        raise ValueError("ADB screencap did not return PNG")
                    digest = hashlib.sha256(png).hexdigest()
                    if digest != previous_hash:
                        with self.lock:
                            over_limit = self.used_bytes + len(png) > self.args.max_mib * 1024 * 1024
                            if not over_limit:
                                self.used_bytes += len(png)
                        if over_limit:
                            self.record("size_limit", role, serial, limitMiB=self.args.max_mib)
                            self.stop.set()
                            break
                        timestamp = time.time_ns() // 1_000_000
                        filename = f"{timestamp}-{digest[:12]}.png"
                        destination = folder / filename
                        with destination.open("xb") as target:
                            target.write(png)
                        os.chmod(destination, 0o600)
                        self.record("screenshot", role, serial,
                                    path=str(destination.relative_to(self.output)),
                                    sha256=digest, bytes=len(png), foreground=focus)
                        previous_hash = digest
            except (OSError, subprocess.SubprocessError, ValueError) as error:
                self.record("screenshot_error", role, serial, error=type(error).__name__, detail=str(error))
            self.stop.wait(max(0.1, self.args.interval - (time.monotonic() - started)))
        self.record("watch_stopped", role, serial)

    def run(self, devices):
        threads = [threading.Thread(target=self.watch_device, args=device, daemon=True) for device in devices]
        for thread in threads:
            thread.start()
        deadline = time.monotonic() + self.args.duration if self.args.duration else None
        try:
            while not self.stop.wait(0.5):
                if deadline and time.monotonic() >= deadline:
                    self.stop.set()
        except KeyboardInterrupt:
            self.stop.set()
        for thread in threads:
            thread.join(timeout=25)


def parse_device(value):
    role, separator, serial = value.partition("=")
    if not separator or not ROLE.fullmatch(role) or not serial or "/" in serial or "\\" in serial:
        raise argparse.ArgumentTypeError("Use --device primary=SERIAL or --device linked=SERIAL")
    return role, serial


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", type=parse_device, action="append", required=True)
    parser.add_argument("--package", default="org.thoughtcrime.securesms")
    parser.add_argument("--output", type=Path, required=True, help="New private directory outside the public documentation tree")
    parser.add_argument("--interval", type=float, default=2.0, help="Seconds between screen polls per device")
    parser.add_argument("--max-mib", type=int, default=2048, help="Combined screenshot disk limit")
    parser.add_argument("--duration", type=float, default=0, help="Optional limit in seconds; zero waits for Ctrl-C")
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb"))
    args = parser.parse_args()
    if not PACKAGE.fullmatch(args.package) or args.interval < 0.5 or args.max_mib < 1 or args.duration < 0:
        parser.error("Invalid package, interval, disk limit, or duration")
    if len({role for role, _ in args.device}) != len(args.device):
        parser.error("Device roles must be unique")
    os.umask(0o077)
    capture = Capture(args)
    signal.signal(signal.SIGTERM, lambda *_: capture.stop.set())
    capture.run(args.device)
    print(capture.manifest)


if __name__ == "__main__":
    main()
