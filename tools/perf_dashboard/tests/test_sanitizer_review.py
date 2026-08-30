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

    def test_compact_secret_names_redact_arbitrary_json_values_without_false_positives(self) -> None:
        line = json.dumps({
            "apikey": ["alpha", "beta"],
            "accesskey": {"inner": True},
            "accesstoken": 9191,
            "clientsecret": False,
            "dbpassword": None,
            "awssecretaccesskey": "quoted value",
            "jwtsecret": {"nested": [1, 2]},
            "keyboard": "keep",
            "monkey": "banana",
            "turnkey": "ready",
        })

        sanitized = json.loads(sanitize_line(line))

        for key in ("apikey", "accesskey", "accesstoken", "clientsecret", "dbpassword", "awssecretaccesskey", "jwtsecret"):
            self.assertEqual("[REDACTED]", sanitized[key])
        self.assertEqual({"keyboard": "keep", "monkey": "banana", "turnkey": "ready"}, {key: sanitized[key] for key in ("keyboard", "monkey", "turnkey")})

    def test_compact_secret_assignments_redact_full_values_without_false_positives(self) -> None:
        line = "apikey=[alpha,beta] accesskey={inner:true} accesstoken=9191 clientsecret=false dbpassword=null awssecretaccesskey='quoted value' jwtsecret=raw-value keyboard=keep monkey=banana turnkey=ready"

        sanitized = sanitize_line(line)

        for value in ("alpha", "beta", "inner", "9191", "false", "null", "quoted value", "raw-value"):
            self.assertNotIn(value, sanitized)
        for assignment in ("keyboard=keep", "monkey=banana", "turnkey=ready"):
            self.assertIn(assignment, sanitized)

    def test_uppercase_compact_secret_names_redact_arbitrary_json_values(self) -> None:
        line = json.dumps({
            "APIKEY": ["upper-alpha", "upper-beta"],
            "ACCESSKEY": {"upper-inner": True},
            "ACCESSTOKEN": 8181,
            "CLIENTSECRET": False,
            "DBPASSWORD": None,
            "AWSSECRETACCESSKEY": "upper quoted value",
            "keyboard": "keep",
            "monkey": "banana",
            "turnkey": "ready",
        })

        sanitized = json.loads(sanitize_line(line))

        secret_keys = (
            "APIKEY", "ACCESSKEY", "ACCESSTOKEN",
            "CLIENTSECRET", "DBPASSWORD", "AWSSECRETACCESSKEY",
        )
        for key in secret_keys:
            self.assertEqual("[REDACTED]", sanitized[key])
        expected_benign = {
            "keyboard": "keep", "monkey": "banana", "turnkey": "ready",
        }
        actual_benign = {key: sanitized[key] for key in expected_benign}
        self.assertEqual(expected_benign, actual_benign)

    def test_uppercase_compact_secret_assignments_redact_full_values(self) -> None:
        line = (
            "APIKEY=[upper-alpha,upper-beta] ACCESSKEY={upper-inner:true} "
            "ACCESSTOKEN=8181 CLIENTSECRET=false DBPASSWORD=null "
            "AWSSECRETACCESSKEY='upper quoted value' "
            "keyboard=keep monkey=banana turnkey=ready"
        )

        sanitized = sanitize_line(line)

        secret_values = (
            "upper-alpha", "upper-beta", "upper-inner", "8181",
            "false", "null", "upper quoted value",
        )
        for value in secret_values:
            self.assertNotIn(value, sanitized)
        for assignment in ("keyboard=keep", "monkey=banana", "turnkey=ready"):
            self.assertIn(assignment, sanitized)


if __name__ == "__main__":
    unittest.main()
