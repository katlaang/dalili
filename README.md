# Dalili Health - Data Model Notes

This README is the single reference for what each backend table does.
The list below is intentionally non-duplicated: each canonical table appears once.

## Active Database

The backend is currently configured for PostgreSQL:

- File: `src/main/resources/application.yml`
- URL: `jdbc:postgresql://localhost:5433/dalili`

Local files like `base.db` and `dalili.db` are legacy SQLite artifacts and are not the active database for the current
backend configuration.

## Canonical Tables (No Duplicates)

1. `active_sessions`: Tracks live authenticated sessions for users.
2. `appointments`: Appointment bookings, reservation/QR tokens, schedule, status, assigned clinician, and check-in
   state.
3. `audit_anchors`: Externalized or chained anchor checkpoints for audit integrity.
4. `audit_events`: Immutable audit log of important system actions.
5. `diagnoses`: Diagnoses recorded under encounters.
6. `encounter_addendums`: Addendum entries appended to signed/finalized encounters.
7. `encounters`: Clinical encounter notes and related metadata.
8. `facility_workflow_config`: Per-facility workflow rules and operating configuration.
9. `kiosk_sessions`: Kiosk device/session lifecycle records.
10. `lab_result_records`: Patient lab results and their metadata.
11. `medication_orders`: Medications prescribed during encounters.
12. `patient_data_authorizations`: Consent/authorization records for data access.
13. `patient_data_transfer_requests`: Requests to share or transfer patient data.
14. `patients`: Core patient profile and identifiers.
15. `portal_messages`: Patient portal message threads/entries.
16. `prescription_renewal_requests`: Refill/renewal requests and status.
17. `queue_tickets`: Queue numbers and patient queue flow state (including patient snapshot fields for kiosk-issued
    tickets).
18. `referral_records`: Referral history and referral destinations.
19. `triage_assessments`: Nurse/triage evaluations and acuity outcomes.
20. `users`: Staff/admin/super-admin/patient-linked user accounts and roles.

## Preventing Duplicate Tables

1. Keep entity-to-table mapping explicit with `@Table(name = "...")` (already used in this codebase).
2. Use one active database target per environment (current default is PostgreSQL on port `5433`).
3. If older/stale tables already exist from previous schema versions, clean them once in PostgreSQL and keep only the
   canonical set listed above.

Helpful check:

```sql
SELECT tablename
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY tablename;
```

If you want strict schema control later, move from `spring.jpa.hibernate.ddl-auto=update` to migrations (
Flyway/Liquibase) and set Hibernate to `validate`.
