#!/usr/bin/env bash
set +e
KEY="JV/qXAikcUoSCCUsqYxLvNjxW/N3KROQtawg3VRFrJs="
B=http://localhost:8080
SK=$B/api/v1/skills
hdr(){ printf "\n### %s\n" "$1"; }
code(){ curl -s -m 20 -o /tmp/b -w "HTTP %{http_code}" "$@"; printf "  "; head -c 300 /tmp/b; echo; }
mut(){ # mutation with spacing
  curl -s -m 20 -o /tmp/b -w "HTTP %{http_code}" "$@"; printf "  "; head -c 260 /tmp/b; echo; sleep 7; }

hdr "SKILL-1 list (before)"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $SK

hdr "SKILL-2 create code-reviewer"
mut -H "X-Amsha-API-Key: $KEY" -H "Content-Type: text/plain" -X POST $SK --data-binary @- <<'YAML'
---
name: code-reviewer
description: Reviews code for security vulnerabilities, anti-patterns, and style
trigger-patterns:
  - "review.*code"
  - "check.*pr"
  - "review.*pull.?request"
  - "security.*audit"
tool-capabilities:
  - GITHUB
  - RECALL_MEMORY_SEARCH
  - ARCHIVAL_MEMORY_SEARCH
max-iterations: 5
---
You are a meticulous code reviewer with deep expertise in Java security.
YAML

hdr "SKILL-3 create researcher"
mut -H "X-Amsha-API-Key: $KEY" -H "Content-Type: text/plain" -X POST $SK --data-binary @- <<'YAML'
---
name: researcher
description: Deep technical research with algorithm analysis and complexity proofs
trigger-patterns:
  - "research.*"
  - "explain.*in.?detail"
  - "how does .* work"
  - ".*algorithm.*complexity"
tool-capabilities:
  - GITHUB
  - RECALL_MEMORY_SEARCH
  - ARCHIVAL_MEMORY_SEARCH
max-iterations: 7
---
You are Saraswati, the knowledge agent. Research technical topics thoroughly.
YAML

hdr "SKILL-4 list (after create)"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $SK

hdr "SKILL-5 upsert code-reviewer (add trigger)"
mut -H "X-Amsha-API-Key: $KEY" -H "Content-Type: text/plain" -X POST $SK --data-binary @- <<'YAML'
---
name: code-reviewer
description: Reviews code - updated with stricter security focus and OWASP alignment
trigger-patterns:
  - "review.*code"
  - "check.*pr"
  - "review.*pull.?request"
  - "security.*audit"
  - "owasp.*review"
tool-capabilities:
  - GITHUB
  - RECALL_MEMORY_SEARCH
  - ARCHIVAL_MEMORY_SEARCH
max-iterations: 6
---
You are a security-focused code reviewer. Prioritise: OWASP Top 10.
YAML

hdr "SKILL-6 list (verify upsert)"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $SK

hdr "SKILL-11 delete researcher"
mut -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" -X DELETE $SK/researcher

hdr "SKILL-12 list (after delete)"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $SK

hdr "SKILL-14 refresh"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $SK/refresh

hdr "SKILL-ERR-1 malformed YAML"
mut -H "X-Amsha-API-Key: $KEY" -H "Content-Type: text/plain" -X POST $SK --data-binary @- <<'YAML'
---
name: broken-skill
description: This frontmatter is never closed
trigger-patterns:
  - "broken.*"
YAML

hdr "SKILL-ERR-2 unknown tool-capability"
mut -H "X-Amsha-API-Key: $KEY" -H "Content-Type: text/plain" -X POST $SK --data-binary @- <<'YAML'
---
name: bad-caps
description: Skill with a deliberately invalid tool-capability name
trigger-patterns:
  - "bad.*caps"
tool-capabilities:
  - GITHUB
  - MEMORY_SEARCH_TYPO
max-iterations: 3
---
You are a test skill. Your bad capability name is silently excluded.
YAML

hdr "SKILL-ERR-3 maxBudgetUsd"
mut -H "X-Amsha-API-Key: $KEY" -H "Content-Type: text/plain" -X POST $SK --data-binary @- <<'YAML'
---
name: budget-override-attempt
description: Attempts to set budget via skill frontmatter
trigger-patterns:
  - "budget.*test"
maxBudgetUsd: 999.99
---
This skill tries to override budget - it must be rejected or ignored.
YAML

hdr "SKILL-ERR-4 delete non-existent"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" -X DELETE $SK/does-not-exist

hdr "SKILL-ERR-5 POST no key"
code -H "Content-Type: text/plain" -X POST $SK --data-binary @- <<'YAML'
---
name: unauthorized-skill
description: Should be rejected by ApiKeyAuthFilter
---
This should never be persisted.
YAML

hdr "SKILL-ERR-6 GET no key"
code -H "Accept: application/json" $SK

hdr "CLEANUP"
mut -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" -X DELETE $SK/code-reviewer
mut -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" -X DELETE $SK/bad-caps
mut -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" -X DELETE $SK/budget-override-attempt
hdr "Final list"
code -H "X-Amsha-API-Key: $KEY" -H "Accept: application/json" $SK
echo "done"
