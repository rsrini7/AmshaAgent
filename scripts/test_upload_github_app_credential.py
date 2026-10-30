import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from upload_github_app_credential import CredentialUploadError, upload_credential


class FakeResponse:
    status = 200

    @staticmethod
    def read(_limit):
        return b'{"configured":true}'


class FakeConnection:
    def __init__(self):
        self.request_arguments = None
        self.closed = False

    def request(self, method, path, *, body, headers):
        self.request_arguments = (method, path, bytes(body), headers)

    @staticmethod
    def getresponse():
        return FakeResponse()

    def close(self):
        self.closed = True


class GitHubAppCredentialUploadTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.secret_directory = self.root / "secrets"
        self.secret_directory.mkdir(mode=0o700)
        self.key_file = self.secret_directory / "app.pem"
        self.key_file.write_bytes(b"test-private-key-payload")
        if os.name != "nt":
            self.secret_directory.chmod(0o700)
            self.key_file.chmod(0o600)
        self.env_file = self.root / ".env"
        self.env_file.write_text(
            "AMSHA_GITHUB_APP_ID=5527419\nAMSHA_API_KEYS_INTERNAL=test-internal-key\n",
            encoding="utf-8",
        )

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_upload_sends_raw_pem_to_local_internal_endpoint(self):
        connection = FakeConnection()
        with patch("upload_github_app_credential.http.client.HTTPConnection", return_value=connection) as factory:
            status = upload_credential(
                self.key_file,
                self.env_file,
                "http://127.0.0.1:8080",
            )

        self.assertEqual(200, status)
        factory.assert_called_once_with("127.0.0.1", 8080, timeout=20)
        method, path, body, headers = connection.request_arguments
        self.assertEqual("PUT", method)
        self.assertEqual("/api/v1/admin/connectors/github-readme/app-credential", path)
        self.assertEqual(b"test-private-key-payload", body)
        self.assertEqual("application/octet-stream", headers["Content-Type"])
        self.assertEqual("test-internal-key", headers["X-Amsha-API-Key"])
        self.assertTrue(connection.closed)

    def test_rejects_non_loopback_upload_target(self):
        with self.assertRaisesRegex(CredentialUploadError, "loopback"):
            upload_credential(self.key_file, self.env_file, "https://example.com")

    def test_rejects_missing_internal_api_credential(self):
        self.env_file.write_text("AMSHA_GITHUB_APP_ID=5527419\n", encoding="utf-8")
        with self.assertRaisesRegex(CredentialUploadError, "AMSHA_API_KEYS_INTERNAL"):
            upload_credential(self.key_file, self.env_file, "http://127.0.0.1:8080")

    @unittest.skipIf(os.name == "nt", "POSIX file permissions are unavailable on Windows")
    def test_requires_owner_only_key_and_directory_permissions(self):
        self.key_file.chmod(0o644)
        with self.assertRaisesRegex(CredentialUploadError, "chmod 600"):
            upload_credential(self.key_file, self.env_file, "http://127.0.0.1:8080")


if __name__ == "__main__":
    unittest.main()
