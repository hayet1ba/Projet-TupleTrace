CREATE TABLE tt.audit_event (
    sequence_no bigint PRIMARY KEY CHECK (sequence_no > 0),
    occurred_at timestamptz NOT NULL,
    organization_id uuid REFERENCES tt.organization,
    actor_id uuid,
    event_type varchar(64) NOT NULL CHECK (event_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    correlation_id uuid NOT NULL,
    target_id uuid,
    previous_hash bytea NOT NULL CHECK (octet_length(previous_hash) = 32),
    event_hash bytea NOT NULL UNIQUE CHECK (octet_length(event_hash) = 32),
    CHECK (actor_id IS NULL OR organization_id IS NOT NULL),
    FOREIGN KEY (organization_id, actor_id) REFERENCES tt.app_user (organization_id, id)
);

CREATE INDEX audit_by_correlation ON tt.audit_event (correlation_id);
CREATE INDEX audit_by_actor ON tt.audit_event (organization_id, actor_id);

CREATE FUNCTION tt.reject_audit_rewrite() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON tt.audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION tt.reject_audit_rewrite();

CREATE FUNCTION tt.append_audit_event(org uuid, actor uuid, kind text, correlation uuid, target uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    next_seq bigint;
    prev bytea;
    stamp timestamptz;
    payload bytea;
BEGIN
    -- Le prochain maillon est choisi après le verrou, détenu jusqu'au commit.
    LOCK TABLE tt.audit_event IN SHARE ROW EXCLUSIVE MODE;
    SELECT sequence_no + 1, event_hash INTO next_seq, prev
      FROM tt.audit_event ORDER BY sequence_no DESC LIMIT 1;
    IF NOT FOUND THEN
        next_seq := 1;
        prev := decode(repeat('00', 32), 'hex');
    END IF;
    stamp := clock_timestamp();
    -- Les champs binaires ont une taille fixe. Un champ nullable porte un octet de présence.
    payload := prev || int8send(next_seq) || timestamptz_send(stamp)
        || coalesce(decode('01','hex') || uuid_send(org), decode('00','hex'))
        || coalesce(decode('01','hex') || uuid_send(actor), decode('00','hex'))
        || uuid_send(correlation)
        || coalesce(decode('01','hex') || uuid_send(target), decode('00','hex'))
        || convert_to(kind, 'UTF8');
    INSERT INTO tt.audit_event
        (sequence_no, occurred_at, organization_id, actor_id, event_type, correlation_id,
         target_id, previous_hash, event_hash)
    VALUES (next_seq, stamp, org, actor, kind, correlation, target, prev, sha256(payload));
END;
$$;

REVOKE ALL ON FUNCTION tt.append_audit_event(uuid, uuid, text, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tt.append_audit_event(uuid, uuid, text, uuid, uuid) TO tt_app;
GRANT SELECT ON tt.audit_event TO tt_audit_reader;
