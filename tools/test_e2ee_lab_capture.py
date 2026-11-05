"""Device-free checks for the one-shot screenshot collector."""

import argparse
import hashlib
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

import e2ee_lab_capture as capture


class LabCaptureTest(unittest.TestCase):
    def test_device_and_foreground_parsing(self):
        self.assertEqual(("primary", "297ca8c8"), capture.parse_device("primary=297ca8c8"))
        with self.assertRaises(argparse.ArgumentTypeError):
            capture.parse_device("../primary=foo")
        self.assertEqual("org.thoughtcrime.securesms", capture.foreground_package(
            b"mCurrentFocus=Window{1 u0 org.thoughtcrime.securesms/org.thoughtcrime.securesms.MainActivity}"))
        self.assertIsNone(capture.foreground_package(b"mCurrentFocus=null"))
        self.assertEqual("106", capture.active_default_network(b"Active default network: 106\n"))

    def test_capture_records_only_changed_signal_foreground_image(self):
        png = capture.PNG_SIGNATURE + b"test-pixels"
        with tempfile.TemporaryDirectory() as temporary:
            args = types.SimpleNamespace(output=Path(temporary) / "run", adb="adb", package="org.thoughtcrime.securesms",
                                         interval=0.5, max_mib=1)
            runner = capture.Capture(args)
            calls = iter([b"1.1.1.1 via wifi", b"Active default network: 106",
                          b"mCurrentFocus=Window{1 u0 org.thoughtcrime.securesms/.MainActivity}", png])

            def fake_adb(*_args, **_kwargs):
                result = next(calls)
                if result == png:
                    runner.stop.set()
                return result

            with patch.object(capture, "adb_call", side_effect=fake_adb):
                runner.watch_device("primary", "serial-1")
            image = next((runner.output / "screenshots/primary").glob("*.png"))
            self.assertEqual(png, image.read_bytes())
            self.assertIn(hashlib.sha256(png).hexdigest(), runner.manifest.read_text())


if __name__ == "__main__":
    unittest.main()
