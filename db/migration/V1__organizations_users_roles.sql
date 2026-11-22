CREATE TABLE tt.organization (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 160),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED')),
    created_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE TABLE tt.app_user (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES tt.organization,
    email text NOT NULL UNIQUE CHECK (email = lower(btrim(email)) AND length(email) BETWEEN 3 AND 254),
    status text NOT NULL DEFAULT 'INVITED' CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    UNIQUE (organization_id, id)
);

CREATE TABLE tt.role_assignment (
    organization_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role_name text NOT NULL CHECK (role_name IN
        ('PROPRIETAIRE', 'GESTIONNAIRE', 'DESTINATAIRE', 'ENQUETEUR', 'ADMINISTRATEUR', 'AUDITEUR')),
    PRIMARY KEY (organization_id, user_id, role_name),
    FOREIGN KEY (organization_id, user_id) REFERENCES tt.app_user (organization_id, id),
    -- Exclusion atomique, y compris si deux transactions attribuent des rôles simultanément.
    EXCLUDE USING gist (user_id WITH =, (role_name = 'ADMINISTRATEUR') WITH <>)
);

GRANT SELECT ON tt.organization, tt.app_user, tt.role_assignment TO tt_app;
