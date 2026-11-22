CREATE TABLE tt.revoked_access_token (
    token_id uuid PRIMARY KEY,
    expires_at timestamptz NOT NULL
);
CREATE INDEX revoked_access_token_expiry ON tt.revoked_access_token (expires_at);
GRANT SELECT ON tt.revoked_access_token TO tt_app;
