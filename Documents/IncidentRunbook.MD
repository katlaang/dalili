# Dalili Health - Audit Incident Runbook

## When Tamper Detection Triggers

**Symptom**: `GET /audit/health` returns `tamperDetected: true`

### Immediate (0-15 min)

1. **DO NOT** restart the system or modify the database
2. Document the timestamp and your identity
3. Screenshot the `/audit/health` response
4. Contact the designated administrator

### Investigation (15-60 min)

1. Check which verification failed:
   - Hash chain verification -> Record was modified
   - Anchor verification -> History was rewritten

2. Run manual verification:
   ```bash
   curl -H "Authorization: Bearer <admin-or-physician-jwt>" \
     https://<api-domain>/api/anchor/verify
   ```

   Notes:
   - The endpoint requires `ADMIN`, `SUPER_ADMIN`, or `PHYSICIAN`.
   - `/audit/health` is public, `/api/anchor/verify` is not.

3. Compare database records with the anchor log inside the app container:
   ```bash
   docker compose -f docker-compose.vm.yml exec app \
     sh -lc 'tail -n 50 /home/spring/.dalili/audit-anchors.log'
   ```

4. Review system access logs for the relevant time period

### Resolution

| Finding                                       | Action                                        |
|-----------------------------------------------|-----------------------------------------------|
| False positive (e.g., DB restore from backup) | Document reason, restart system               |
| Actual tampering                              | Preserve evidence, engage legal counsel       |
| Hardware/corruption                           | Replace system, restore from last good backup |

### Resetting After False Positive

Only after confirming the cause was benign:

1. Document the incident and reason for reset
2. Restart the application (clears `tamperDetected` flag)
3. Create a fresh anchor:
   ```bash
   curl -X POST -H "Authorization: Bearer <admin-or-physician-jwt>" \
     https://<api-domain>/api/anchor/create
   ```
4. Verify the system: `GET https://<api-domain>/audit/health`

---

## Contacts

| Role                 | Contact                    |
|----------------------|----------------------------|
| System Administrator | [Configure per deployment] |
| Security Contact     | [Configure per deployment] |

---

*Runbook version 1.1 | March 2026*
