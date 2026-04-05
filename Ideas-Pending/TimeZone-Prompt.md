All four violations are valid — here's the corrected implementation for each:

## Fix 1 — `static final` Patterns (Invariant 9.1)

```java
public class CronTimeResolver {

    // All patterns static final — compiled once, never inline
    private static final Pattern IN_MINUTES_PATTERN =
            Pattern.compile("(?:in|after)\\s+(\\d+)\\s*min(?:utes?)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern AT_TIME_PATTERN =
            Pattern.compile("at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern VALID_CRON_PATTERN =
            Pattern.compile("^\\d{1,2}\\s+\\d{1,2}\\s+[*\\d]+\\s+[*\\d]+\\s+[*\\d]+$");

    // Fix 2 — Negation guard (Invariant 4.11)
    private static final Pattern NEGATION_GUARD =
            Pattern.compile("\\b(?:not|don't|do not|never|cancel|stop|remove)\\b",
                    Pattern.CASE_INSENSITIVE);

    public static boolean isValidCron(String cron) {
        return cron != null && VALID_CRON_PATTERN.matcher(cron.trim()).matches();
    }

    public static ZonedDateTime resolveTargetTime(String msg, ZonedDateTime nowUtc, ZoneId userZone) {
        // Fix 2 — Bail immediately on negation
        if (NEGATION_GUARD.matcher(msg).find()) {
            return null; // caller must handle null → do not schedule
        }

        var m1 = IN_MINUTES_PATTERN.matcher(msg);
        if (m1.find()) {
            return nowUtc.plusMinutes(Long.parseLong(m1.group(1)));
        }

        var m2 = AT_TIME_PATTERN.matcher(msg);
        if (m2.find()) {
            int hour   = Integer.parseInt(m2.group(1));
            int minute = m2.group(2) != null ? Integer.parseInt(m2.group(2)) : 0;
            if ("pm".equalsIgnoreCase(m2.group(3)) && hour < 12) hour += 12;
            if ("am".equalsIgnoreCase(m2.group(3)) && hour == 12) hour  = 0;
            ZonedDateTime local = ZonedDateTime.now(userZone)
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            if (local.isBefore(ZonedDateTime.now(userZone))) local = local.plusDays(1);
            return local.withZoneSameInstant(ZoneOffset.UTC);
        }

        return nowUtc.plusMinutes(5); // safe fallback
    }
}
```

***

## Fix 3 — Prompt via Qute Template (Invariant 4.4)

Add the new fields to the **existing** coordinator persona template, not a new file:

**`src/main/resources/templates/personas/coordinator.txt`** — append inside the existing scheduling section:

```
{#if SUGGESTED_CRON}
## Scheduling (PRE-COMPUTED — DO NOT RECALCULATE)
Current local time : {CURRENT_LOCAL_TIME}
Pre-computed cron  : {SUGGESTED_CRON}  ← use this EXACTLY as-is
UTC minute         : {TARGET_UTC_MINUTE}
UTC hour           : {TARGET_UTC_HOUR}

Use cron="{SUGGESTED_CRON}" in your tool call.
DO NOT recalculate, convert, or modify this value.
DO NOT append a timezone name to the cron expression.
Confirm the schedule to the user in their local time only.
{/if}
```

The `{#if SUGGESTED_CRON}` block means the section is only rendered when a cron context is active — no impact on non-scheduling sessions.

***

## Fix 4 — Transaction-Free Zone (Invariants 5.8/5.9)

`CronTimeResolver.resolveTargetTime()` is pure computation — no DB, no CDI, no JTA. Call it in the **pipeline handler before the reflex**, not inside any `@Transactional` reflex:

```java
// In CommandDispatchHandler (already Transaction-Free by architecture)
// BEFORE routing to reflexRegistry:
ZonedDateTime targetUtc = CronTimeResolver.resolveTargetTime(raw, nowUtc, userZone);

if (targetUtc != null) {
    context.setData("SUGGESTED_CRON",    targetUtc.getMinute() + " " + targetUtc.getHour() + " * * *");
    context.setData("TARGET_UTC_MINUTE", String.valueOf(targetUtc.getMinute()));
    context.setData("TARGET_UTC_HOUR",   String.valueOf(targetUtc.getHour()));
    context.setData("CURRENT_LOCAL_TIME",
            ZonedDateTime.now(userZone).format(DateTimeFormatter.ofPattern("HH:mm z")));
}
// Then reflexRegistry.processIfReflex(...) as normal
```

The Qute renderer picks up `context.getData()` map values when it renders the persona template — the `instance.data(...)` calls you already have.

***

## Answer to Your Two Questions

**Q1 — Provide refined Java?** → Done above, all four violations addressed.

**Q2 — Check `CronReflex` location?** → Not needed since `CronTimeResolver` is pure static computation called from `CommandDispatchHandler` which is already in the transaction-free pipeline layer. No reflex holds a JTA transaction as long as the resolver is called before `reflexRegistry.processIfReflex()`.

---

The core problem is you're asking a weak model to do **timezone arithmetic** — the single thing small LLMs hallucinate most reliably. The fix is the **antigravity principle: do all the math in Java, give the model zero arithmetic to perform**.

## The Root Problem

Your current approach still requires the model to:
1. Parse `2026-04-05T16:28:00Z` from a string
2. Add `+05:30` offset mentally
3. Convert "in 30 minutes" to a future UTC timestamp
4. Format a cron expression correctly

Llama-4-scout fails at step 2 or 3 every time. It's not a prompt problem — it's a capability ceiling.

***

## The Antigravity Fix — Java Does All Math

### Step 1 — Inject Pre-Computed Values, Not Raw Timestamps

Stop injecting `CURRENT_UTC_ISO` alone. Pre-compute everything the model would need to "think":

```java
ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);
ZoneId userZone     = ZoneId.of("Asia/Kolkata"); // from user profile
ZonedDateTime nowIst = nowUtc.withZoneSameInstant(userZone);

// Parse the user's natural language to extract "in X minutes" or "at HH:mm"
// BEFORE building the system prompt (see Step 2 below)
ZonedDateTime targetUtc = resolveTargetTime(userMessage, nowUtc, userZone);

instance.data("CURRENT_UTC_ISO",    nowUtc.toString());
instance.data("CURRENT_LOCAL_TIME", nowIst.format(DateTimeFormatter.ofPattern("HH:mm z")));
instance.data("TARGET_UTC_MINUTE",  String.valueOf(targetUtc.getMinute()));
instance.data("TARGET_UTC_HOUR",    String.valueOf(targetUtc.getHour()));
instance.data("SUGGESTED_CRON",     targetUtc.getMinute() + " " + targetUtc.getHour() + " * * *");
```

### Step 2 — `resolveTargetTime()` in Java (Pre-Flight Parser)

```java
ZonedDateTime resolveTargetTime(String msg, ZonedDateTime nowUtc, ZoneId userZone) {
    // "in X minutes" / "after X minutes"
    var inMinutes = Pattern.compile("(?:in|after)\\s+(\\d+)\\s*min", Pattern.CASE_INSENSITIVE);
    var m1 = inMinutes.matcher(msg);
    if (m1.find()) {
        return nowUtc.plusMinutes(Long.parseLong(m1.group(1)));
    }

    // "at HH:mm" or "at H pm" in user's local timezone
    var atTime = Pattern.compile("at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE);
    var m2 = atTime.matcher(msg);
    if (m2.find()) {
        int hour   = Integer.parseInt(m2.group(1));
        int minute = m2.group(2) != null ? Integer.parseInt(m2.group(2)) : 0;
        if ("pm".equalsIgnoreCase(m2.group(3)) && hour < 12) hour += 12;
        if ("am".equalsIgnoreCase(m2.group(3)) && hour == 12) hour = 0;
        ZonedDateTime localTarget = ZonedDateTime.now(userZone)
            .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        // if already past, schedule for tomorrow
        if (localTarget.isBefore(ZonedDateTime.now(userZone))) {
            localTarget = localTarget.plusDays(1);
        }
        return localTarget.withZoneSameInstant(ZoneOffset.UTC);
    }

    return nowUtc.plusMinutes(5); // safe fallback
}
```

### Step 3 — Updated System Prompt for the Model

Now the model has **nothing to compute** — it just uses the pre-served values:

```
The current time is: {CURRENT_LOCAL_TIME}

The user wants to schedule a task.
Based on their request, the pre-computed schedule is:
  → UTC minute : {TARGET_UTC_MINUTE}
  → UTC hour   : {TARGET_UTC_HOUR}
  → Cron       : {SUGGESTED_CRON}

Use this cron expression EXACTLY as given: {SUGGESTED_CRON}
Do NOT recalculate, convert, or modify the cron expression.
Do NOT add a timezone to the cron expression.

Your ONLY job: confirm the schedule with the user in their local time ({CURRENT_LOCAL_TIME} timezone)
and call the create_cron tool with cron="{SUGGESTED_CRON}".
```

The model's role is reduced from **mathematician** to **confirmer + tool caller** — well within llama-4-scout's capability.

***

### Step 4 — Java-Side Cron Validator (Safety Net)

After the LLM generates the `create_cron` tool call, validate before persisting:

```java
// In your CronReflex or cron creation handler
String llmCron = extractedCron; // from tool args
String javaCron = preComputedCron; // from resolveTargetTime()

if (!isValidCron(llmCron)) {
    log.warnf("LLM produced invalid cron '%s', using pre-computed '%s'", llmCron, javaCron);
    llmCron = javaCron; // silent fallback — never fail the user
}
```

```java
boolean isValidCron(String cron) {
    if (cron == null) return false;
    // basic 5-field check: M H * * *
    return cron.trim().matches("\\d{1,2}\\s+\\d{1,2}\\s+[*\\d]+\\s+[*\\d]+\\s+[*\\d]+");
}
```

***

## Summary of Changes Needed

| Location | Change |
|---|---|
| `CronReflex` / cron creation handler | Add `resolveTargetTime()` pre-parser |
| System prompt template | Replace arithmetic instructions with pre-computed `SUGGESTED_CRON` |
| Cron persistence layer | Add `isValidCron()` validator with `javaCron` fallback |
| `instance.data(...)` calls | Inject `TARGET_UTC_MINUTE`, `TARGET_UTC_HOUR`, `SUGGESTED_CRON` |

The key insight: **treat the LLM as a natural language parser and UX layer, not a calculator**. The moment you need it to do math, move that math to Java.

----