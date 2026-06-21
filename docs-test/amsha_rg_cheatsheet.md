# Amsha Agent - Ripgrep & Maven Test Diagnostics Cheatsheet

A reference for codebase search patterns and test diagnostics used across the Amsha Agent multi-module project.

---

## 1. General Codebase Search

### 1.1 Generic Term Search (excluding test sources)

```bash
rg "<SEARCH-TERM>" --type java -l --ignore-file <(echo "*/test/*")
```

> Replace `<SEARCH-TERM>` with any symbol, class name, or keyword.  
> `-l` lists only matching file paths (not lines), keeping output clean.

---

### 1.2 Autonomy Level Enum / Field Search

```bash
rg "autonomyLevel|AutonomyLevel|SUPERVISED|FULL|READONLY" --type java -l --ignore-file <(echo "*/test/*")
```

Finds all Java files referencing autonomy-related identifiers - useful when refactoring the `AutonomyLevel` enum or tracing where levels are enforced.

---

### 1.3 `ChannelType.fromString` Usages

```bash
rg "ChannelType.fromString" . --type java
```

Locates every call-site that deserializes a `ChannelType` from a string - helpful during channel-API refactors.

---

### 1.4 Literal / Magic Number Search in Repositories

```bash
rg "15" . --type java -g "*Repository.java"
```

Scoped to `*Repository.java` files only. Useful for hunting magic numbers (e.g., default page sizes, timeouts) in data-access layer classes.

---

### 1.5 Stalled Task / Recovery Pattern Search

```bash
# Find stalled task references
rg "stalled" . --type java

# Find recovery logic references
rg "recovery" . --type java
```

Used to audit task-scheduler resilience logic - stalled-agent detection and recovery flows in the autonomous scheduling subsystem.

---

## 2. Surefire Test Report Aggregation

### 2.1 Quick Total Test Count

```bash
rg -u -g "**/target/surefire-reports/*.xml" "tests=" \
  | awk -F'tests="' '{sum += $2} END {print "Total Tests: " sum}'
```

Fast single-number summary across all modules.

---

### 2.2 Full Test Summary (Total / Failures / Errors / Skipped)

```bash
rg -u -g "**/target/surefire-reports/TEST-*.xml" "<testsuite" | awk -F' ' '{
    for(i=1; i<=NF; i++) {
        if($i ~ /tests=/)    { split($i, a, "\""); t += a[2] }
        if($i ~ /errors=/)   { split($i, a, "\""); e += a[2] }
        if($i ~ /failures=/) { split($i, a, "\""); f += a[2] }
        if($i ~ /skipped=/)  { split($i, a, "\""); s += a[2] }
    }
}
END { printf "Total: %d | Failures: %d | Errors: %d | Skipped: %d\n", t, f, e, s }'
```

Aggregates across all `TEST-*.xml` surefire reports in every module's `target/` directory.

---

### 2.3 Extract Failed Test Names + Error Messages

```bash
rg -u -t xml -z -U -P \
  '(?s)<testcase\s+name="([^"]+)"\s+classname="([^"]+)"[^>]*>.*?<(failure|error)[^>]*message="([^"]*)"' \
  -r '[ERROR] $2.$1: $4'
```

Uses PCRE2 multiline mode (`-U -P`) to extract structured failure output:  
`[ERROR] com.amsha.app.SomeTest.methodName: assertion message here`

---

## 3. Skipped & Failed Test Discovery

### 3.1 Find Skipped Tests (Linux/macOS - ripgrep)

```bash
rg -u -g "**/target/surefire-reports/TEST-*.xml" "<testcase[^>]*>.*<skipped"
```

> **Note:** Change `<skipped` to `<failure` or `<error` for failed tests.

---

### 3.2 Find Skipped Tests (Windows - PowerShell)

```powershell
Get-ChildItem -Recurse -Filter "TEST-*.xml" -Path "**/target/surefire-reports/" |
    Select-Xml -XPath "//testcase[skipped]" |
    ForEach-Object {
        $node = $_.Node
        "[SKIPPED] $($node.classname).$($node.name)"
    }
```

> **For failed tests:** change the XPath to `//testcase[failure]` or `//testcase[error]`.

---

## 4. Targeted Test Execution with Debug Output

### 4.1 Run a Single Test Method with Full Debug + Error Filtering

```bash
./mvnw clean test \
  -pl amsha-app -am \
  -Dtest=AgentResourceTest#testFullAgentLifecycle \
  "-Dsurefire.failIfNoSpecifiedTests=false" \
  -Dquarkus.log.level=DEBUG \
  2>&1 | grep -A 5 "500\|ERROR\|Exception"
```

**Flags explained:**

| Flag | Purpose |
|---|---|
| `-pl amsha-app -am` | Run only `amsha-app` module, building dependencies (`-am`) first |
| `-Dtest=Class#method` | Target a single test method |
| `-Dsurefire.failIfNoSpecifiedTests=false` | Prevent build failure if test filter matches nothing |
| `-Dquarkus.log.level=DEBUG` | Enable full Quarkus DEBUG logging |
| `grep -A 5 "500\|ERROR\|Exception"` | Surface HTTP 500s, ERRORs, and stack traces (5 lines of context) |

> **Tip:** Stacktraces are sometimes emitted only at `DEBUG` level in Quarkus - if an exception is missing context, `DEBUG` mode here is the right lever.
