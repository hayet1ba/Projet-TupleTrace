CREATE TABLE tt.signing_key_version (
    key_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid REFERENCES tt.organization,
    purpose text NOT NULL CHECK (purpose IN ('ACCESS_TOKEN', 'SEAL', 'REGISTRY', 'REPORT', 'AUDIT')),
    version integer NOT NULL CHECK (version > 0),
    store_alias varchar(128) NOT NULL UNIQUE CHECK (store_alias ~ '^[A-Za-z0-9._-]+$'),
    status text NOT NULL DEFAULT 'STAGED' CHECK (status IN ('STAGED', 'ACTIVE', 'RETIRED', 'REVOKED')),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    CHECK ((purpose = 'ACCESS_TOKEN') = (organization_id IS NULL)),
    UNIQUE NULLS NOT DISTINCT (organization_id, purpose, version)
);

-- NULL représente la plateforme, qui possède elle aussi au plus une version active par usage.
CREATE UNIQUE INDEX signing_key_one_active ON tt.signing_key_version (organization_id, purpose)
    NULLS NOT DISTINCT WHERE status = 'ACTIVE';

GRANT SELECT ON tt.signing_key_version TO tt_app;
