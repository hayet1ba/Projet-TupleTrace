ALTER TABLE tt.signing_key_version ADD COLUMN verify_until timestamptz;
ALTER TABLE tt.signing_key_version DROP CONSTRAINT signing_key_version_status_check;

-- V2 n'attestait ni le protocole ni le type des clés déjà référencées.
UPDATE tt.signing_key_version SET status = 'REVOKED' WHERE purpose = 'ACCESS_TOKEN';
ALTER TABLE tt.signing_key_version ADD CHECK (
    CASE WHEN purpose = 'ACCESS_TOKEN' THEN status IN ('ACTIVE','VERIFY_ONLY','REVOKED')
         ELSE status IN ('STAGED','ACTIVE','RETIRED','REVOKED') END
), ADD CHECK ((status = 'VERIFY_ONLY') = (verify_until IS NOT NULL));

CREATE FUNCTION tt.rotate_access_key(expected_id uuid, next_id uuid) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    current_id uuid;
    next_version integer;
BEGIN
    -- Sérialise les opérations de la plateforme, y compris la toute première activation.
    PERFORM pg_advisory_xact_lock(817030, 1);
    SELECT key_id INTO current_id FROM tt.signing_key_version
        WHERE purpose = 'ACCESS_TOKEN' AND status = 'ACTIVE';
    IF current_id IS DISTINCT FROM expected_id THEN
        RAISE EXCEPTION 'Active signing key changed' USING ERRCODE = '40001';
    END IF;
    UPDATE tt.signing_key_version SET status = 'VERIFY_ONLY', verify_until = clock_timestamp() + interval '15 minutes'
        WHERE key_id = current_id;
    SELECT coalesce(max(version), 0) + 1 INTO next_version FROM tt.signing_key_version WHERE purpose = 'ACCESS_TOKEN';
    INSERT INTO tt.signing_key_version (key_id, purpose, version, store_alias, status)
        VALUES (next_id, 'ACCESS_TOKEN', next_version, 'paseto-' || next_id, 'ACTIVE');
END;
$$;

CREATE FUNCTION tt.revoke_access_key(target_id uuid) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(817030, 1);
    UPDATE tt.signing_key_version SET status = 'REVOKED', verify_until = NULL
        WHERE purpose = 'ACCESS_TOKEN' AND key_id = target_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Unknown access key'; END IF;
END;
$$;

REVOKE ALL ON FUNCTION tt.rotate_access_key(uuid, uuid), tt.revoke_access_key(uuid) FROM PUBLIC;
