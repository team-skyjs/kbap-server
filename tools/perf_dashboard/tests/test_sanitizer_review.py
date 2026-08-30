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

    def test_structural_secret_components_cover_camel_pascal_snake_and_kebab_names(self) -> None:
        line = json.dumps({
            "OPENAI_API_KEY": ["one", "two"],
            "githubToken": {"nested": True},
            "userPassword": False,
            "encryptionKey": None,
            "JwtSecret": 123,
            "service credential": {"value": "hidden"},
            "keyboard": "keep",
            "monkey": "banana",
            "turnkey": "ready",
        })

        sanitized = json.loads(sanitize_line(line))

        for key in ("OPENAI_API_KEY", "githubToken", "userPassword", "encryptionKey", "JwtSecret", "service credential"):
            self.assertEqual("[REDACTED]", sanitized[key])
        self.assertEqual({"keyboard": "keep", "monkey": "banana", "turnkey": "ready"}, {key: sanitized[key] for key in ("keyboard", "monkey", "turnkey")})

    def test_non_json_structural_names_redact_full_compound_values(self) -> None:
        line = 'OPENAI_API_KEY=[one,two] githubToken={nested:true} userPassword=false encryptionKey: null "service credential": [space,value] keyboard=keep monkey=banana turnkey=ready'

        sanitized = sanitize_line(line)

        for secret in ("one", "two", "nested", "false", "null", "space", "value"):
            self.assertNotIn(secret, sanitized)
        self.assertIn("keyboard=keep", sanitized)
        self.assertIn("monkey=banana", sanitized)
        self.assertIn("turnkey=ready", sanitized)


if __name__ == "__main__":
    unittest.main()
