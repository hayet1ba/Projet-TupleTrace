"""Contrôle le WAR livré, y compris les archives embarquées, sans écrire leur contenu sur disque."""
import argparse
import io
import json
from pathlib import Path
import re
import zipfile


FORBIDDEN = (".p12", ".pfx", ".pkcs12", ".jks", ".jceks", ".keystore", ".bks", ".bcfks",
             ".key", ".pk8", ".pkcs8", ".ppk")
ARCHIVES = {".jar", ".war", ".ear", ".zip"}
PRIVATE_ARMOR = re.compile(rb"-----BEGIN (?:[A-Z0-9 -]+ )?PRIVATE KEY(?: BLOCK)?-----", re.I)
# Signatures des magasins JKS/JCEKS et des clés privées OpenSSH et PuTTY.
PRIVATE_MAGIC = (bytes.fromhex("feedfeed"), bytes.fromhex("cececece"), b"openssh-key-v1\0", b"PuTTY-User-Key-File-")
MAX_UNPACKED = 256 * 1024 * 1024


class Rejected(ValueError):
    pass


def der_bounds(data, offset):
    """Bornes du contenu d'une valeur DER à longueur définie."""
    if offset + 2 > len(data):
        return None
    length = data[offset + 1]
    start = offset + 2
    if length >= 128:
        count = length - 128
        if not 1 <= count <= 4 or start + count > len(data):
            return None
        length = int.from_bytes(data[start:start + count], "big")
        start += count
    return (start, start + length) if start + length <= len(data) else None


def private_der(data):
    if not data.startswith(b"\x30"):
        return False
    sequence = der_bounds(data, 0)
    if not sequence:
        return False
    start, end = sequence
    first = data[start:start + 3]
    tag = data[start + 3:start + 4]
    if first == b"\x02\x01\x03" and tag == b"\x30":  # PKCS#12 PFX version 3
        return True
    if first in (b"\x02\x01\x00", b"\x02\x01\x01"):
        if tag in (b"\x02", b"\x04"):  # RSA/DSA ou EC privée
            return True
        algorithm = der_bounds(data, start + 3)
        return tag == b"\x30" and algorithm and data[algorithm[1]:algorithm[1]+1] == b"\x04"
    # EncryptedPrivateKeyInfo : AlgorithmIdentifier suivi d'un OCTET STRING.
    algorithm = der_bounds(data, start)
    if data[start:start+1] != b"\x30" or not algorithm:
        return False
    oid, next_tag = algorithm
    return data[oid:oid+1] == b"\x06" and next_tag < end and data[next_tag] == 4


def private_jwk(obj):
    if isinstance(obj, list):
        return any(private_jwk(value) for value in obj)
    if isinstance(obj, dict):
        if (obj.get("kty") in ("RSA", "EC", "OKP") and "d" in obj) or (obj.get("kty") == "oct" and "k" in obj):
            return True
        return any(private_jwk(value) for value in obj.values())
    return False


def check_war(path):
    remaining = MAX_UNPACKED

    def inspect(archive, depth):
        nonlocal remaining
        if depth > 12:
            raise Rejected("profondeur d'archive excessive")
        with zipfile.ZipFile(archive) as package:
            for entry in package.infolist():
                if entry.is_dir():
                    continue
                suffix = Path(entry.filename).suffix.lower()
                if entry.filename.lower().endswith(FORBIDDEN):
                    raise Rejected("extension de clé ou de magasin interdite")
                remaining -= entry.file_size
                if remaining < 0:
                    raise Rejected("budget de décompression dépassé")
                data = package.read(entry)
                nested = io.BytesIO(data)
                if suffix in ARCHIVES or zipfile.is_zipfile(nested):
                    inspect(nested, depth + 1)
                elif data.startswith(PRIVATE_MAGIC) or PRIVATE_ARMOR.search(data) or private_der(data):
                    raise Rejected("contenu de clé privée ou de magasin interdit")
                elif data.lstrip()[:1] in (b"{", b"["):
                    try:
                        obj = json.loads(data)
                    except (UnicodeDecodeError, json.JSONDecodeError):
                        continue
                    if private_jwk(obj):
                        raise Rejected("JWK privée ou symétrique interdite")

    inspect(path, 0)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("war", type=Path)
    args = parser.parse_args()
    try:
        check_war(args.war)
    except Rejected as error:
        parser.exit(1, f"WAR refusé : {error}\n")
    except Exception:
        parser.exit(1, "WAR refusé : archive illisible ou inspection incomplète\n")
