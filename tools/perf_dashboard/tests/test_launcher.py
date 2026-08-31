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
        self.real_python = Path(os.environ.get("PYTHON", "/usr/bin/python3"))
        self.calls = self.root / "calls"
        self.jwt_secret = "launcher-jwt-secret"
        mint_token = self.launcher.parents[2] / "k6" / "mint-token.py"
        self.access_token = subprocess.run(
            [str(self.real_python), str(mint_token), "35", "2"],
            env={**os.environ, "JWT_SECRET": self.jwt_secret},
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        for name in ("k6", "aws", "jq", "session-manager-plugin", "docker"):
            command = self.fake_bin / name
            command.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            command.chmod(0o700)
        aws = self.fake_bin / "aws"
        aws.write_text(
            "#!/usr/bin/env bash\n"
            "if [[ \" $* \" == *' sts get-caller-identity '* ]]; then printf '118178010621\\n'; exit 0; fi\n"
            "exit 64\n",
            encoding="utf-8",
        )
        python = self.fake_bin / "python3"
        python.write_text(
            "#!/usr/bin/env bash\n"
            "if [[ \"${1:-}\" == '-m' ]]; then printf '%s\\n' \"$*\" >>\"$FAKE_CALLS\"; exit 0; fi\n"
            "exec \"$REAL_PYTHON\" \"$@\"\n",
            encoding="utf-8",
        )
        python.chmod(0o700)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def environment(self) -> dict[str, str]:
        return {
            "PATH": f"{self.fake_bin}:/usr/bin:/bin",
            "FIXTURE_PATH": str(self.fixture),
            "REAL_PYTHON": str(self.real_python),
            "FAKE_CALLS": str(self.calls),
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
        self.assertIn("JWT_SECRET and ACCESS_TOKEN", result.stderr)
        self.assertNotIn(self.jwt_secret, result.stderr)

    def test_successful_launch_does_not_print_access_token(self) -> None:
        environment = self.environment()
        environment["JWT_SECRET"] = self.jwt_secret
        environment["ACCESS_TOKEN"] = self.access_token

        result = subprocess.run(
            [str(self.launcher), "--port", "9876"],
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )

        output = result.stdout + result.stderr
        self.assertEqual(0, result.returncode)
        self.assertIn("-m tools.perf_dashboard.server --port 9876", self.calls.read_text(encoding="utf-8"))
        self.assertNotIn(self.access_token, output)


if __name__ == "__main__":
    unittest.main()
