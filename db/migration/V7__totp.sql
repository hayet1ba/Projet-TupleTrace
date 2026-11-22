CREATE TABLE tt.totp_factor (
    user_id uuid PRIMARY KEY REFERENCES tt.app_user ON DELETE CASCADE,
    factor_id uuid NOT NULL UNIQUE,
    ciphertext bytea NOT NULL CHECK (octet_length(ciphertext)=48), -- nonce GCM, graine de 20 octets, tag
    last_step bigint CHECK (last_step >= 0), -- NULL jusqu'à la confirmation
    failures integer NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 5),
    window_start timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION tt.privileged_role(role_name text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$ SELECT role_name IN ('PROPRIETAIRE','ADMINISTRATEUR','AUDITEUR') $$;

CREATE VIEW tt.totp_status AS SELECT user_id, factor_id, last_step IS NOT NULL AS active FROM tt.totp_factor;
GRANT SELECT ON tt.totp_status TO tt_app;

-- Chaque essai réserve une place avant déchiffrement, même si le processus s'arrête ensuite.
CREATE FUNCTION tt.start_totp(subject uuid, org uuid)
RETURNS TABLE (factor_id uuid, ciphertext bytea, step bigint)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
    UPDATE tt.totp_factor f SET
        failures=CASE WHEN f.window_start <= now()-interval '5 minutes' THEN 1 ELSE f.failures+1 END,
        window_start=CASE WHEN f.window_start <= now()-interval '5 minutes' THEN now() ELSE f.window_start END
    FROM tt.app_user u JOIN tt.organization o ON o.id=u.organization_id
    WHERE f.user_id=subject AND u.id=subject AND o.id=org AND u.status='ACTIVE' AND o.status='ACTIVE'
      AND (f.failures<5 OR f.window_start <= now()-interval '5 minutes')
    RETURNING f.factor_id,f.ciphertext,floor(extract(epoch FROM now())/30)::bigint
$$;

CREATE FUNCTION tt.accept_totp(subject uuid, org uuid, factor uuid, counter bigint)
RETURNS timestamptz LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
DECLARE accepted_at timestamptz;
BEGIN
    PERFORM 1 FROM tt.totp_factor WHERE user_id=subject FOR UPDATE;
    UPDATE tt.totp_factor f SET last_step=counter, failures=0, window_start=now()
    FROM tt.app_user u JOIN tt.organization o ON o.id=u.organization_id
    WHERE f.user_id=subject AND u.id=subject AND o.id=org AND u.status='ACTIVE' AND o.status='ACTIVE'
      AND f.factor_id=factor AND (f.last_step IS NULL OR f.last_step<counter)
      AND counter BETWEEN floor(extract(epoch FROM clock_timestamp())/30)::bigint-1
                      AND floor(extract(epoch FROM clock_timestamp())/30)::bigint+1
    RETURNING date_trunc('second', clock_timestamp()) INTO accepted_at;
    RETURN accepted_at;
END
$$;

CREATE FUNCTION tt.remove_totp(subject uuid, factor uuid, strong_at timestamptz) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    PERFORM 1 FROM tt.totp_factor WHERE user_id=subject FOR UPDATE;
    DELETE FROM tt.totp_factor WHERE user_id=subject AND factor_id=factor AND last_step IS NOT NULL
        AND strong_at BETWEEN clock_timestamp()-interval '5 minutes' AND clock_timestamp();
    RETURN FOUND;
END
$$;

CREATE FUNCTION tt.logout_token(token uuid, expiry timestamptz) RETURNS void
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $$
    INSERT INTO tt.revoked_access_token VALUES(token,expiry) ON CONFLICT DO NOTHING
$$;
REVOKE ALL ON FUNCTION tt.start_totp(uuid,uuid), tt.accept_totp(uuid,uuid,uuid,bigint),
    tt.remove_totp(uuid,uuid,timestamptz), tt.logout_token(uuid,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tt.start_totp(uuid,uuid), tt.accept_totp(uuid,uuid,uuid,bigint),
    tt.remove_totp(uuid,uuid,timestamptz), tt.logout_token(uuid,timestamptz) TO tt_app;
