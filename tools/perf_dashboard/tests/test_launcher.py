import os
import subprocess
import tempfile
import unittest
from pathlib import Path


class DashboardLauncherTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.fake_bin = self.root / "bin"
        self.fake_bin.mkdir()
        self.fixture = self.root / "fixture.json"
        self.fixture.write_text("{}", encoding="utf-8")
        self.launcher = Path(__file__).resolve().parents[3] / "scripts" / "perf" / "dashboard.sh"
        for name in ("python3", "k6", "aws", "jq"):
            command = self.fake_bin / name
            command.write_text("#!/usr/bin/env bash\nprintf '%s\\n' \"$*\"\n", encoding="utf-8")
            command.chmod(0o700)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def environment(self) -> dict[str, str]:
        return {
            "PATH": f"{self.fake_bin}:/usr/bin:/bin",
            "FIXTURE_PATH": str(self.fixture),
        }

    def test_missing_access_token_fails_without_printing_a_value(self) -> None:
        environment = self.environment()

        result = subprocess.run(
            [str(self.launcher)],
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(2, result.returncode)
        self.assertEqual("error: ACCESS_TOKEN is required", result.stderr.strip())

    def test_successful_launch_does_not_print_access_token(self) -> None:
        environment = self.environment()
        environment["ACCESS_TOKEN"] = "launcher-super-secret"

        result = subprocess.run(
            [str(self.launcher), "--port", "9876"],
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode)
        self.assertIn("-m tools.perf_dashboard.server --port 9876", output)
        self.assertNotIn("launcher-super-secret", output)


if __name__ == "__main__":
    unittest.main()
