-- ═══════════════════════════════════════════════════════════════════
-- Phase 45: One-time backfill script for existing compaction_summary rows
-- Run ONCE on production database after deploying Phase 45.
-- ═══════════════════════════════════════════════════════════════════

-- 1. Attach the FTS database if running manually
-- ATTACH DATABASE './data/amsha-fts.db' AS fts;

-- 2. Backfill userId from audit_event (existing compaction_summary rows)
UPDATE compaction_summary
SET user_id = (
    SELECT ae.user_id FROM audit_event ae
    WHERE ae.session_id = compaction_summary.session_id
    LIMIT 1
)
WHERE user_id IS NULL;

-- 3. Sentinel for sessions with no audit trail
UPDATE compaction_summary
SET user_id = 'unknown'
WHERE user_id IS NULL;

-- 4. Populate Standalone FTS index (Phase 45 Revision)
-- We use sessionId for mapping instead of rowid for total isolation
INSERT INTO fts.compaction_summary_fts(sessionId, summary)
SELECT sessionId, summary FROM main.compaction_summary
WHERE sessionId NOT IN (SELECT sessionId FROM fts.compaction_summary_fts);
