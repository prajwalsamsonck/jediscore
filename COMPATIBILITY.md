# Redis Compatibility Matrix

This document tracks JediCore's command-level compatibility with Redis. It is a living
document, updated every phase as commands are implemented.

## Legend

| Status | Meaning |
|--------|---------|
| ✅ Done | Implemented and covered by tests, behaviour matches Redis. |
| 🚧 Partial | Implemented, but with documented gaps (see Notes). |
| 📋 Planned | Scheduled for a specific phase. |
| ❌ N/A | Not planned (e.g. cluster-only or deprecated). |

## Protocol support

| Feature | Status | Notes |
|---------|--------|-------|
| RESP2 | 📋 Planned | Phase 1. |
| RESP3 | 📋 Planned | Phase 1. |
| Inline commands | 📋 Planned | Phase 1. |

## Command matrix

| Command | Status | Notes |
|---------|--------|-------|
| _(none yet)_ | — | Phase 0 establishes tooling only; commands begin in Phase 2 (`PING`/`ECHO`/`HELLO`). |

<!--
  Maintenance: as each command lands, add a row above with its status and any
  behavioural notes (edge cases, RESP3 differences, deviations from Redis). Group
  rows by family once there are enough to warrant subheadings (Strings, Lists, …).
-->
