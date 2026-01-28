# Dalili Health - Audit Security Model

## What We Protect Against

| Threat                        | How                                                       |
|-------------------------------|-----------------------------------------------------------|
| **Audit record modification** | SHA-256 hash chain - any change breaks the chain          |
| **Audit record deletion**     | Database triggers prevent deletion; chain breaks detected |
| **History rewriting**         | External anchors prove chain state at point in time       |
| **Unauthorized access**       | Session-based guards; all access logged                   |

## What We Detect

- Any modification to historical audit records (hash verification fails)
- Any deletion of audit records (chain break)
- History rewriting after anchoring (anchor verification fails)
- Unauthorized access attempts (session guard violations)

**On detection**: System sets `AuditHealthStatus.tamperDetected = true`, blocking sensitive operations until
investigated.

## What We Do NOT Protect Against

| Threat                           | Why Acceptable                                                                                                                     |
|----------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| **Full system + key compromise** | Attacker could forge future events, but cannot rewrite history (anchors prove it). Physical security is a facility responsibility. |
| **Denial of service**            | Availability is an infrastructure concern, not audit concern. Backups exist.                                                       |
| **Social engineering**           | All actions are logged and attributable. Training is an operational control.                                                       |

## Residual Risk

An attacker with both database admin access AND the ability to modify the anchor file could theoretically forge records
going forward. They still cannot rewrite history without external anchor verification failing.

**Mitigation for production**: Store anchors in Azure Blob Storage with immutability policies. Then even full system
compromise cannot forge history.

---

*Document version 1.0 | January 2026 | For regulatory review*