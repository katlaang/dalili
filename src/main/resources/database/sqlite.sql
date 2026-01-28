CREATE TRIGGER prevent_audit_update
    BEFORE UPDATE
    ON audit_events
BEGIN
    SELECT RAISE(FAIL, 'Audit events are immutable');
END;

CREATE TRIGGER prevent_audit_delete
    BEFORE DELETE
    ON audit_events
BEGIN
    SELECT RAISE(FAIL, 'Audit events cannot be deleted');
END;
