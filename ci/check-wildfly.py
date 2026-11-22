"""Vérifications sur l'instance WildFly de test, liée exclusivement à la boucle locale."""
import base64
import http.client
import json
import os
import ssl


def request(connection, method, path, headers=None):
    try:
        connection.request(method, path, headers=headers or {})
        response = connection.getresponse()
        return response.status, response.getheaders(), response.read()
    finally:
        connection.close()


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    # WildFly de test génère un certificat auto-signé. Aucune URL distante n'est acceptée.
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE

    def https(method, path, headers=None):
        return request(http.client.HTTPSConnection("127.0.0.1", 8443, timeout=10,
                                                  context=context), method, path, headers)

    status, headers, body = https("GET", "/api/health")
    require(status == 200, f"GET /api/health : {status}")
    require(json.loads(body) == {"status": "UP"}, "Réponse de santé inattendue")
    require(any(k.lower() == "content-type" and v.startswith("application/json")
                for k, v in headers), "Type de contenu inattendu")
    print("GET /api/health public en HTTPS : 200")

    for headers in ({}, {"Authorization": "Bearer invalid"}):
        status, _, _ = https("GET", "/api/private", headers)
        require(status == 401, f"GET /api/private : {status}, attendu 401")
    print("GET /api/private anonyme ou avec un Bearer invalide : 401")

    # Ces identités existent uniquement dans le domaine éphémère du job CI.
    for username, variable, expected in (
        ("ci-auditeur", "TT_TEST_AUDITEUR", 200),
        ("ci-destinataire", "TT_TEST_DESTINATAIRE", 403),
    ):
        if variable in os.environ:
            pair = f"{username}:{os.environ[variable]}".encode()
            basic = base64.b64encode(pair).decode("ascii")
            status, _, _ = https("GET", "/api/private", {"Authorization": f"Basic {basic}"})
            require(status == expected, f"GET /api/private pour {username} : {status}")
            print(f"GET /api/private pour {username} : {status}")

    status, _, _ = https("POST", "/api/health")
    require(status == 403, f"POST /api/health : {status}, attendu 403")
    print("POST /api/health sans autorisation : 403")

    status, headers, _ = request(http.client.HTTPConnection("127.0.0.1", 8080, timeout=10),
                                 "GET", "/api/health")
    redirect = status in (301, 302, 303, 307, 308) and any(
        k.lower() == "location" and v.startswith("https://") for k, v in headers)
    require(redirect or status == 403, f"Garantie CONFIDENTIAL en HTTP : {status}")
    print(f"GET /api/health en HTTP : {status}")


if __name__ == "__main__":
    main()
