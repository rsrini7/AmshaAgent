-- Seed ample extracted_memories for the resolved internal API-key user so the
-- self-evolve synthesize/reconcile lifecycle has real, varied content to act on.
--
-- IMPORTANT: created_at MUST use the exact format the JDBI Instant mapper
-- (TemporalUtils.parseIsoLex) accepts: yyyy-MM-dd'T'HH:mm:ss.SSSX
-- i.e. millisecond precision + 'Z' offset. Omitting millis (or using a space
-- instead of 'T') makes Instant.parse fail on read-back.
PRAGMA busy_timeout=10000;

-- Target userId = the user the internal API key resolves to at runtime
-- (bootstrap ownerId). Override via -v when calling sqlite3 if it differs.
-- Default placeholder; the validator computes and rewrites this before applying.
DELETE FROM extracted_memories WHERE user_id = 'INTERNAL_USER_PLACEHOLDER';

INSERT INTO extracted_memories
  (id, session_id, user_id, record_type, content, confidence, content_hash, source_event_ids, created_at)
VALUES
  ('seed-fp-01','seed-sess-1','INTERNAL_USER_PLACEHOLDER','FAILURE_PATTERN','Tool host_exec failed 3 times because HTML artifact was empty; retry with explicit output path fixed it.',0.82,'h-fp-01','ev-1','2026-07-15T10:00:00.000Z'),
  ('seed-fp-02','seed-sess-1','INTERNAL_USER_PLACEHOLDER','FAILURE_PATTERN','Recall memory search returned empty when query had trailing spaces; trim user input before search.',0.74,'h-fp-02','ev-2','2026-07-15T10:05:00.000Z'),
  ('seed-th-01','seed-sess-2','INTERNAL_USER_PLACEHOLDER','TOOL_HINT','Use the schedule_task tool for any cron-like scheduling instead of raw shell at.',0.88,'h-th-01','ev-3','2026-07-15T10:10:00.000Z'),
  ('seed-th-02','seed-sess-2','INTERNAL_USER_PLACEHOLDER','TOOL_HINT','Prefer archival_memory_insert for long-term facts; core memory fills fast with verbose notes.',0.79,'h-th-02','ev-4','2026-07-15T10:15:00.000Z'),
  ('seed-th-03','seed-sess-3','INTERNAL_USER_PLACEHOLDER','TOOL_HINT','garuda tool is best for bulk file operations across workspaces.',0.71,'h-th-03','ev-5','2026-07-15T10:20:00.000Z'),
  ('seed-up-01','seed-sess-3','INTERNAL_USER_PLACEHOLDER','USER_PREFERENCE','User prefers concise bullet summaries over long prose in final answers.',0.90,'h-up-01','ev-6','2026-07-15T10:25:00.000Z'),
  ('seed-up-02','seed-sess-4','INTERNAL_USER_PLACEHOLDER','USER_PREFERENCE','User wants SQL verified with PRAGMA busy_timeout before writes on the live meta DB.',0.83,'h-up-02','ev-7','2026-07-15T10:30:00.000Z'),
  ('seed-rp-01','seed-sess-4','INTERNAL_USER_PLACEHOLDER','RESOLUTION_PATTERN','For concurrent write contention, wrap with immediate transaction mode to avoid SQLITE_BUSY.',0.86,'h-rp-01','ev-8','2026-07-15T10:35:00.000Z'),
  ('seed-ec-01','seed-sess-5','INTERNAL_USER_PLACEHOLDER','ERROR_CONTEXT','Error in tool github: rate limit 403, backoff 60s and retry resolved it.',0.77,'h-ec-01','ev-9','2026-07-15T10:40:00.000Z'),
  ('seed-wa-01','seed-sess-5','INTERNAL_USER_PLACEHOLDER','WORKAROUND','For FTS5 non-ASCII search, only escape syntax characters; do not escape the unicode bytes.',0.81,'h-wa-01','ev-10','2026-07-15T10:45:00.000Z'),
  ('seed-tm-01','seed-sess-6','INTERNAL_USER_PLACEHOLDER','TOOL_MISUSE_PATTERN','Calling self_evolve_reflect twice in one turn caused collapse; emit only one action per turn.',0.85,'h-tm-01','ev-11','2026-07-15T10:50:00.000Z'),
  ('seed-fp-03','seed-sess-6','INTERNAL_USER_PLACEHOLDER','FAILURE_PATTERN','Budget hard limit was exceeded by one call; check hard limit before the calling step.',0.78,'h-fp-03','ev-12','2026-07-15T10:55:00.000Z'),
  ('seed-th-04','seed-sess-7','INTERNAL_USER_PLACEHOLDER','TOOL_HINT','Use web_fetch for static pages; browser_navigate only when JS rendering is required.',0.80,'h-th-04','ev-13','2026-07-15T11:00:00.000Z'),
  ('seed-up-03','seed-sess-7','INTERNAL_USER_PLACEHOLDER','USER_PREFERENCE','User prefers responses in English even when the prompt is in another language.',0.69,'h-up-03','ev-14','2026-07-15T11:05:00.000Z');

-- Seed synthesized_directives in SHADOW state for the same user so the
-- reconcile / disable / delete lifecycle can be exercised without an LLM call.
-- evaluation_hits=3 -> reconcileAll() promotes SHADOW -> ACTIVE
-- evaluation_misses=5 -> reconcileAll() auto-disables the directive
DELETE FROM synthesized_directives WHERE user_id = 'INTERNAL_USER_PLACEHOLDER';

INSERT INTO synthesized_directives
  (id, user_id, text, state, confidence, source_record_ids, synthesis_prompt_hash,
   evaluation_hits, evaluation_misses, created_at, updated_at)
VALUES
  ('seed-dir-promote','INTERNAL_USER_PLACEHOLDER','Prefer schedule_task over raw shell for cron-like scheduling.',
   'SHADOW',0.85,'seed-th-01','ph-1',3,0,'2026-07-15T11:10:00.000Z','2026-07-15T11:10:00.000Z'),
  ('seed-dir-disable','INTERNAL_USER_PLACEHOLDER','Always echo generated HTML directly into chat for user preview.',
   'SHADOW',0.40,'seed-ec-01','ph-2',0,5,'2026-07-15T11:11:00.000Z','2026-07-15T11:11:00.000Z');
