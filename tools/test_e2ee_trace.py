"""Local, device-free checks for the ADB trace helper."""
import argparse
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import e2ee_trace


class TraceToolTest(unittest.TestCase):
    def test_labels_reject_shell_and_path_characters(self):
        for value in ["../secret", "abc;id", "a b", "a\nb", ""]:
            with self.assertRaises(argparse.ArgumentTypeError):
                e2ee_trace.label(value)
        self.assertEqual("scan-qr_01", e2ee_trace.label("scan-qr_01"))

    def test_control_is_explicit_and_failure_is_reported(self):
        command = ["trace", "--serial", "emulator-5554", "--package", "org.example.signal", "--adb", "adb", "status"]
        with patch.object(sys, "argv", command), patch("subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, b"Broadcast completed: result=-1\n", b"")
            e2ee_trace.main()
            self.assertEqual(["adb", "-s", "emulator-5554", "shell", "am", "broadcast"], run.call_args.args[0][:6])
            self.assertNotIn("shell", run.call_args.kwargs)
            run.return_value = subprocess.CompletedProcess([], 0, b"Broadcast completed: result=0\n", b"")
            with self.assertRaises(RuntimeError):
                e2ee_trace.main()

    def test_export_validates_names_and_does_not_overwrite(self):
        name = "123_12345678-1234-1234-1234-123456789abc-0001.jsonl"
        with tempfile.TemporaryDirectory() as directory:
            command = ["trace", "--serial", "emulator-5554", "--package", "org.example.signal", "--adb", "adb", "export", "--output", directory]
            replies = [subprocess.CompletedProcess([], 0, (name + "\n../escape.jsonl\n").encode(), b""), subprocess.CompletedProcess([], 0, b'{"kind":"test"}\n', b"")]
            with patch.object(sys, "argv", command), patch("subprocess.run", side_effect=replies):
                e2ee_trace.main()
            self.assertEqual([name], [p.name for p in Path(directory).iterdir()])
            with patch.object(sys, "argv", command), patch("subprocess.run", side_effect=replies):
                with self.assertRaises(FileExistsError):
                    e2ee_trace.main()


if __name__ == "__main__":
    unittest.main()
