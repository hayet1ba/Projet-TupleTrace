"""Provisionne les deux identités BASIC du serveur WildFly jetable, avant son démarrage."""
import hashlib
import os
from pathlib import Path
import sys


def provision(directory):
    users = directory / "application-users.properties"
    roles = directory / "application-roles.properties"
    if "#$REALM_NAME=ApplicationRealm$" not in users.read_text(encoding="utf-8") or not roles.is_file():
        raise SystemExit("Configuration ApplicationRealm inattendue")

    verifiers = []
    memberships = []
    for role in ("AUDITEUR", "DESTINATAIRE"):
        user = "ci-" + role.lower()
        password = os.environ["TT_TEST_" + role]
        if not password:
            raise SystemExit("Mot de passe de test manquant")
        # Format HEX(MD5(user:realm:password)) du properties-realm livré avec WildFly 42.
        digest = hashlib.md5(f"{user}:ApplicationRealm:{password}".encode()).hexdigest()
        verifiers.append(f"{user}={digest}")
        memberships.append(f"{user}={role}")

    users.chmod(0o600)
    for path, entries in ((users, verifiers), (roles, memberships)):
        with path.open("a", encoding="ascii") as output:
            output.write("\n" + "\n".join(entries) + "\n")


if __name__ == "__main__":
    provision(Path(sys.argv[1]))
