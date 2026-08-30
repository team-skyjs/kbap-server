import json
import unittest

from tools.perf_dashboard.events import sanitize_line


class SanitizerReviewTest(unittest.TestCase):
    def test_nested_json_redacts_secret_keys_with_arbitrary_value_types(self) -> None:
        line = json.dumps({
            "accessToken": "access-value",
            "api_key": 12345,
            "nested": {"jwt-secret": False, "token": ["one", "two"], "password": None},
            "message": "Authorization: Bearer bearer-value",
            "keyboard": "mechanical",
            "monkey": True,
        })

        sanitized = json.loads(sanitize_line(line))

        self.assertEqual("[REDACTED]", sanitized["accessToken"])
        self.assertEqual("[REDACTED]", sanitized["api_key"])
        self.assertEqual("[REDACTED]", sanitized["nested"]["jwt-secret"])
        self.assertEqual("[REDACTED]", sanitized["nested"]["token"])
        self.assertEqual("[REDACTED]", sanitized["nested"]["password"])
        self.assertNotIn("bearer-value", sanitized["message"])
        self.assertEqual("mechanical", sanitized["keyboard"])
        self.assertTrue(sanitized["monkey"])

    def test_non_json_assignments_redact_complete_compound_values_without_false_positives(self) -> None:
        line = "apiKey=[one,two] JWT_SECRET: {nested:true} access-token='quoted value' keyboard=[keep,this] monkey: banana turnkey=value"

        sanitized = sanitize_line(line)

        for secret in ("one", "two", "nested", "quoted value"):
            self.assertNotIn(secret, sanitized)
        self.assertIn("keyboard=[keep,this]", sanitized)
        self.assertIn("monkey: banana", sanitized)
        self.assertIn("turnkey=value", sanitized)


if __name__ == "__main__":
    unittest.main()
