"""Gestion locale des clés PASETO avec keytool et psql. Le répertoire est un volume privé persistant."""
import argparse
import os
from pathlib import Path
import subprocess
import uuid


def database_call(sql, **arguments):
    options = [f"--set={name}={value}" for name, value in arguments.items()]
    subprocess.run(["psql", "-X", "-w", "-U", "tt_migrator", "-v", "ON_ERROR_STOP=1", *options],
                   input=sql, text=True, check=True, stdout=subprocess.DEVNULL,
                   env={**os.environ, "PGPASSWORD": os.environ["TT_MIGRATION_PASSWORD"]})


def rotate(expected):
    directory = Path(os.environ["TT_KEY_DIR"])
    if not directory.is_absolute() or not directory.is_dir():
        raise ValueError("TT_KEY_DIR doit désigner un volume privé existant avec un chemin absolu")
    if os.name == "posix" and directory.stat().st_mode & 0o077:
        raise ValueError("Accès au répertoire de clés trop large (mode attendu 0700)")
    os.umask(0o077)
    kid = uuid.uuid4()
    alias = f"paseto-{kid}"
    path = directory / f"{alias}.p12"
    if path.exists():
        raise FileExistsError(path)
    common = ["-storetype", "PKCS12", "-keystore", str(path), "-alias", alias,
              "-storepass:env", "TT_KEYSTORE_PASSWORD"]
    subprocess.run(["keytool", "-genkeypair", "-keyalg", "Ed25519", "-sigalg", "Ed25519",
                    "-dname", f"CN={alias}", "-validity", "3650", *common], check=True)
    subprocess.run(["keytool", "-list", *common], check=True, stdout=subprocess.DEVNULL)
    with path.open("rb+") as store:
        os.fsync(store.fileno())
    if os.name == "posix":
        fd = os.open(directory, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    # Un commit incertain peut avoir réussi. Garder le magasin pour permettre la reprise.
    database_call("SELECT tt.rotate_access_key(NULLIF(:'expected','')::uuid, :'kid'::uuid);",
                  expected=expected or "", kid=kid)
    print(kid)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    activate = commands.add_parser("rotate")
    activate.add_argument("--expected", type=uuid.UUID, help="kid actif observé (omettre si aucun)")
    commands.add_parser("revoke").add_argument("kid", type=uuid.UUID)
    args = parser.parse_args()
    if args.operation == "rotate":
        rotate(args.expected)
    else:
        database_call("SELECT tt.revoke_access_key(:'kid'::uuid);", kid=args.kid)
