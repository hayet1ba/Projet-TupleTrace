-- Vérifier l'appartenance lors de l'ajout sans rattacher les événements passés
-- à l'organisation actuelle d'un compte transféré.
ALTER TABLE tt.audit_event
    DROP CONSTRAINT audit_event_organization_id_actor_id_fkey,
    ADD FOREIGN KEY (actor_id) REFERENCES tt.app_user(id);

CREATE FUNCTION tt.check_audit_membership() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
BEGIN
    IF NEW.actor_id IS NOT NULL THEN
        PERFORM 1 FROM tt.app_user WHERE id=NEW.actor_id AND organization_id=NEW.organization_id FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Audit actor outside organization' USING ERRCODE='23503';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER audit_check_membership BEFORE INSERT ON tt.audit_event
    FOR EACH ROW EXECUTE FUNCTION tt.check_audit_membership();
