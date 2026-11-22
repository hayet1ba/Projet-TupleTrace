import base64
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from check_war import check_war, Rejected


def archive(members):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as package:
        for name, content in members.items():
            package.writestr(name, content)
    out.seek(0)
    return out


class WarInspectionTest(unittest.TestCase):
    def test_rejects_inert_store_in_final_war(self):
        with tempfile.TemporaryDirectory() as directory:
            war = Path(directory) / "test.war"
            marker = b"inert-packaging-review"
            war.write_bytes(archive({"WEB-INF/packaging-review.p12": marker}).getvalue())
            result = subprocess.run([sys.executable, Path(__file__).with_name("check_war.py"), war],
                                    capture_output=True)
            self.assertEqual(1, result.returncode)
            self.assertIn(b"extension", result.stderr)
            self.assertNotIn(marker, result.stdout + result.stderr)

    def test_inspects_nested_archives(self):
        for name, data in (
            ("case.PFX", b"fixture"),
            ("renamed.dat", b"-----BEGIN " + b"ENCRYPTED PRIVATE KEY" + b"-----\nfixture"),
            ("renamed.bin", bytes.fromhex("feedfeed") + b"fixture"),
            ("keys.json", b'{"keys":[{"kty":"OKP","d":"fixture"}]}'),
            ("renamed.der", bytes.fromhex("300a02010030020600040100")),
        ):
            with self.subTest(name=name):
                inner = archive({name: data})
                jar = archive({"resource.bin": inner.getvalue()})
                with self.assertRaises(Rejected):
                    check_war(archive({"WEB-INF/lib/nested.jar": jar.getvalue()}))

    def test_accepts_public_material(self):
        public = bytes.fromhex("302a300506032b6570032100") + bytes(range(32))
        cert = b"-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----"
        pem = b"-----BEGIN PUBLIC KEY-----\n" + base64.b64encode(public) + b"\n-----END PUBLIC KEY-----"
        check_war(archive({"cert.pem": cert, "ed25519.pem": pem, "ed25519.der": public,
                           "jwks.json": '{"keys":[{"kty":"OKP","crv":"Ed25519","x":"public"}]}'}))

    def test_unreadable_dependency_fails_closed(self):
        with self.assertRaises(zipfile.BadZipFile):
            check_war(archive({"WEB-INF/lib/broken.jar": b"fixture"}))
