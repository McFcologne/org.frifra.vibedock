"""Exercise the real selection/probe block with fake serial tools; never touch hardware."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class FlashPortTest(unittest.TestCase):
    def run_selection(self, choice, busy=False, chip="auto"):
        source = (Path(__file__).parents[1] / "scripts/flash.sh").read_text()
        block = source[source.index("# Select the port before opening"):source.index("# 2. Board:")]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / "python"
            fake.write_text('''#!/bin/sh
if [ "$1" = -m ]; then
  printf '%s\\n' "$*" >> "$PROBE_LOG"
  printf 'Chip is ESP32-S3 (QFN56)\\nMAC: aa:bb:cc:dd:ee:ff\\n'
else
  printf 'USB fixture\\n'
fi
''')
            fake.chmod(0o700)
            log = root / "probes"
            prefix = '''set -euo pipefail
found=$'/dev/fixture-a\\n/dev/fixture-b'
board=fixture
sleep() { :; }
'''
            prefix += "lsof() { return " + ("0" if busy else "1") + "; }\n"
            prefix += 'python="$FAKE_PYTHON"\nchip="$TEST_CHIP"\n'
            result = subprocess.run(["bash", "-c", prefix + block], input=choice, text=True,
                                    capture_output=True, env={**os.environ, "FAKE_PYTHON": str(fake),
                                    "PROBE_LOG": str(log), "TEST_CHIP": chip})
            return result, log.read_text() if log.exists() else ""

    def final_flash(self, chip):
        source = (Path(__file__).parents[1] / "scripts/flash.sh").read_text()
        block = source[source.rindex('if [ "$chip" = esp8266 ]; then'):]
        prefix = 'set -eu\nchip="$TEST_CHIP"\nport=/dev/fixture\nsize=4\nimage=fixture.bin\nesptool() { printf "%s\\n" "$*"; }\n'
        return subprocess.run(["bash", "-c", prefix + block], capture_output=True, text=True,
                              env={**os.environ, "TEST_CHIP": chip})

    def test_m1_starts_user_code_after_writing_without_control_line_reset(self):
        result = self.final_flash("esp8266")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--after soft_reset write_flash", result.stdout)
        self.assertNotIn("--after hard_reset", result.stdout)
        self.assertNotIn("--after no_reset", result.stdout)

    def test_esp32_keeps_its_hardware_reset(self):
        result = self.final_flash("esp32s3")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--after hard_reset write_flash 0x0 fixture.bin", result.stdout)

    def test_only_selected_port_is_queried(self):
        result, probes = self.run_selection("2\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--port /dev/fixture-b", probes)
        self.assertNotIn("fixture-a", probes)
        self.assertEqual(1, len(probes.splitlines()))

    def test_invalid_selection_never_probes(self):
        result, probes = self.run_selection("0\n")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", probes)

    def test_busy_ports_never_probe(self):
        result, probes = self.run_selection("1\n", busy=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", probes)

    def test_wrong_chip_never_probes_another_device(self):
        result, probes = self.run_selection("1\n", chip="esp8266")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--port /dev/fixture-a", probes)
        self.assertNotIn("fixture-b", probes)
        self.assertEqual(1, len(probes.splitlines()))


if __name__ == "__main__":
    unittest.main()
