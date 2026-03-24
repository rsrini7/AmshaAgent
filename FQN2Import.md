# ✅ 🔍 Find usages (exclude `import` / `package`)

```powershell
rg --pcre2 -n --no-heading -g "*.java" '^(?!\s*(import|package)).*\b(?:java|jakarta|com|org|dev)\.[a-zA-Z0-9_.]+\.[A-Z][A-Za-z0-9_]+'
```

---

# ✅ 📦 Extract unique FQNs

```powershell
rg --pcre2 -o -g "*.java" '\b(?:java|jakarta|com|org|dev)\.[a-zA-Z0-9_.]+\.[A-Z][A-Za-z0-9_]+' |
rg -v '^\s*(import|package) ' |
sort -u
```

👉 Faster + simpler than previous version (no need for replace hack)

---

# ✅ 🔎 Quick scan (lightweight check)

```powershell
rg --pcre2 -g "*.java" '^(?!\s*(import|package)).*\b(java|jakarta|com|org|dev)\.[\w.]+\.[A-Z]\w*'
```

---

# ✅ 🔢 Count total occurrences

```powershell
rg --pcre2 -g "*.java" -c '^(?!\s*(import|package)).*\b(java|jakarta|com|org|dev)\.[\w.]+\.[A-Z]\w*' |
Measure-Object -Sum
```

---

# ✅ 🧪 File list (files that need fixing)

```powershell
rg --pcre2 -l -g "*.java" '^(?!\s*(import|package)).*\b(java|jakarta|com|org|dev)\.[\w.]+\.[A-Z]\w*'
```

---

**Prompt:**

Scan all Java files using:

```
rg --pcre2 -g "*.java" '^(?!\s*(import|package)).*\b(java|jakarta|com|org|dev)\.[\w.]+\.[A-Z]\w*'
```

For every match:

* Replace fully-qualified class names with simple class names inside method bodies, variables, generics, annotations, and casts
* Add corresponding `import` statements at the top
* Do NOT modify `package` or existing `import` lines
* Do NOT introduce `import static`
* Avoid duplicate imports
* Keep FQN only if needed for disambiguation (`// fully qualified to disambiguate`)

After changes:

* Organize imports
* Ensure code compiles without errors