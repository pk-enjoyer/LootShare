---
date: 2026-08-21
type: specification
tags:
  - feature
  - community-lootshare
ai-first: true
---

For future Claude: Community Lootshare will replace legacy chat-command value entry with Party-synchronised manual GP attribution. The host can attribute GP to any approved Party member; guests can attribute GP only to themselves when the host enables that Party policy. No OSRS chat parsing or chat commands are retained.

## Overview

Manual GP attribution records exceptional or non-standard value in the same immutable proposal and settlement flow as captured loot. It is a host-owned Party policy, persisted with the session and synchronised to every member.

## Functional requirements

- When a host adds a positive GP amount to an approved Party member, the system shall create and synchronise an accepted manual-GP proposal owned by that member.
- When self-attribution is enabled by the host, an approved non-host member shall be able to submit a positive manual-GP proposal only for themselves.
- While self-attribution is disabled, the system shall reject a guest manual-GP proposal.
- Where a manual-GP proposal is submitted by someone other than its owner, the system shall accept it only when the sender is the active host.
- When Party host settings are synchronised, the system shall include the self-attribution policy.
- The system shall not parse OSRS chat messages or support `!add` commands for manual values.

## Non-functional requirements

- Manual values must be positive, bounded to the existing shared-loot maximum, and overflow-safe.
- Guests cannot enable the policy locally or attribute value to other members.
- A manual contribution must use the existing proposal/decision persistence and settlement calculation paths.

## Acceptance criteria

- Given an approved member and a host, when the host adds 1,000,000 GP to that member, then every Party client shows an accepted 1,000,000 GP contribution for that member and the settlement updates.
- Given self-attribution is disabled, when a guest attempts to add GP, then no proposal is created.
- Given self-attribution is enabled, when an approved guest adds GP, then the host accepts that guest-owned proposal and the settlement updates.
- Given a pending or excluded member, when manual GP is requested for them, then no accepted contribution is created.

## Error handling

| Condition | Result |
| --- | --- |
| Blank, zero, negative, or too-large amount | Reject without mutation |
| Unknown/non-approved target | Reject without mutation |
| Guest attributes another member | Reject without mutation |
| Guest self-attribution disabled by host | Reject without mutation |

## Implementation checklist

- [ ] Add host policy config and Party payload field.
- [ ] Add explicit manual-proposal metadata and validation.
- [ ] Add host and guest sidebar actions.
- [ ] Add focused domain, Party-message, controller, and panel tests.
- [ ] Remove the legacy chat-value stack after its replacement is verified.
