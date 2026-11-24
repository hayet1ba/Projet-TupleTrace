CREATE TABLE tt.password_credential (
    user_id uuid PRIMARY KEY REFERENCES tt.app_user ON DELETE CASCADE,
    profile text NOT NULL DEFAULT 'argon2id-v19-m65536-t3-p1'
        CHECK (profile = 'argon2id-v19-m65536-t3-p1'),
    salt bytea NOT NULL CHECK (octet_length(salt) = 16),
    verifier bytea NOT NULL CHECK (octet_length(verifier) = 32)
);

CREATE TABLE tt.login_failure (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    account_key bytea,
    origin inet NOT NULL
);
CREATE INDEX login_failure_by_account ON tt.login_failure (account_key, occurred_at DESC);
CREATE INDEX login_failure_by_origin ON tt.login_failure (origin, occurred_at DESC);
CREATE INDEX login_failure_expiry ON tt.login_failure (occurred_at);

-- La transaction réserve une tentative avant Argon2. Seul un refus confirme la réservation.
CREATE FUNCTION tt.reserve_password_check(login text, peer inet)
RETURNS TABLE (decision text, user_id uuid, salt bytea, verifier bytea)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    email_key text := lower(btrim(login));
    account_hash bytea := sha256(convert_to(email_key, 'UTF8'));
    stamp timestamptz;
    cutoff timestamptz;
    failures integer;
    last_failure timestamptz;
BEGIN
    IF login IS NULL OR length(login) > 1024 OR peer IS NULL
       OR masklen(peer) <> (CASE family(peer) WHEN 4 THEN 32 ELSE 128 END) THEN
        RAISE EXCEPTION 'Invalid login context';
    END IF;
    -- Ordre commun à toutes les instances. Une collision de hash ne fait que sérialiser deux appels.
    PERFORM pg_advisory_xact_lock(817031, hashtext(host(peer)));
    PERFORM pg_advisory_xact_lock(817032, hashtext(email_key));
    stamp := clock_timestamp();
    cutoff := stamp - interval '15 minutes';
    DELETE FROM tt.login_failure WHERE id IN (
        SELECT f.id FROM tt.login_failure f WHERE f.occurred_at <= cutoff
        ORDER BY f.occurred_at LIMIT 64 FOR UPDATE SKIP LOCKED
    );
    IF (SELECT count(*) FROM tt.login_failure f WHERE f.origin=peer AND f.occurred_at > cutoff) >= 20 THEN
        decision := 'ORIGIN_LIMIT';
        RETURN NEXT;
        RETURN;
    END IF;
    SELECT count(*), max(f.occurred_at) INTO failures, last_failure
    FROM tt.login_failure f WHERE f.account_key=account_hash AND f.occurred_at > cutoff;
    IF failures >= 5 AND stamp < last_failure + make_interval(secs => least(300, 1 << least(9, failures-5))) THEN
        -- Une tentative pendant le délai compte pour l'origine, sans prolonger le délai du compte.
        INSERT INTO tt.login_failure (occurred_at, origin) VALUES (stamp, peer);
        decision := 'ACCOUNT_DELAY';
    ELSE
        INSERT INTO tt.login_failure (occurred_at, account_key, origin) VALUES (stamp, account_hash, peer);
        decision := 'CHECK';
        SELECT u.id, c.salt, c.verifier INTO user_id, salt, verifier
        FROM tt.app_user u JOIN tt.organization o ON o.id=u.organization_id
        JOIN tt.password_credential c ON c.user_id=u.id
        WHERE u.email=email_key AND u.status='ACTIVE' AND o.status='ACTIVE';
    END IF;
    RETURN NEXT;
END;
$$;

REVOKE ALL ON FUNCTION tt.reserve_password_check(text, inet) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tt.reserve_password_check(text, inet) TO tt_app;
