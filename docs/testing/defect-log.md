# Defect log

One row per bug task. Status lives on the squad board; this is the history.

| Id | Found in | Symptom | Owner | Fixed by |
|---|---|---|---|---|
| T-501 | S1 gate / CI | Admin write APIs return 400 instead of 403 for non-admin roles | L3 | duplicate of T-502 |
| T-502 | E2E RBAC matrix | Admin API validates body before `@PreAuthorize` -> 400 instead of 403 | L3 | #36 |
| T-505 | S1 gate | Main CI `e2e-api` red: `KNOWN_BUGS` still marks the fixed T-502 endpoints as `test.fail` | L6 | open |
