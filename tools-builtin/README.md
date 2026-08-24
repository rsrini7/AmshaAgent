# Amsha Built-in Tools Module (`amsha-tools-builtin`)

This module provides the 15 standard built-in `AgentTool` implementations, Lucene dynamic tool search index, memory management tools, and host execution wrappers for the Amsha Agent framework.

## Tools Provided

1. `web_fetch` (`SmartWebFetchTool`)
2. `brave_web_search` (`BraveWebSearchTool`)
3. `serper_web_search` (`SerperWebSearchTool`)
4. `host_exec` (`HostExecTool`)
5. `github` (`GithubTool`)
6. `schedule_task` (`ScheduleTaskTool`)
7. `manage_schedule` (`ManageScheduleTool`)
8. `manage_checklist` (`CheckListTool`)
9. `sandbox_exec` (`SandboxExecTool`)
10. `archival_memory_insert` (`ArchivalMemoryInsertTool`)
11. `archival_memory_search` (`ArchivalMemorySearchTool`)
12. `core_memory_read` (`CoreMemoryReadTool`)
13. `core_memory_append` (`CoreMemoryAppendTool`)
14. `core_memory_replace` (`CoreMemoryReplaceTool`)
15. `recall_memory_search` (`RecallMemorySearchTool`)

## Configuration Properties

| Property | Default | Description |
|---|---|---|
| `amsha.tools.brave.api-key` | `none` | Brave search API key |
| `amsha.tools.serper.api-key` | `none` | Serper search API key |
| `amsha.tools.web-fetch.user-agent` | `AmshaAgent/0.1.0` | User-Agent header for web fetch |
| `amsha.tools.web-fetch.connect-timeout-seconds` | `10` | Web fetch HTTP connect timeout in seconds |
| `amsha.tools.web-fetch.read-timeout-seconds` | `20` | Web fetch HTTP read timeout in seconds |
| `amsha.tools.web-fetch.max-chars` | `8192` | Max characters extracted from fetched web pages |
| `amsha.tools.web-fetch.tor-enabled` | `false` | Enable Tor SOCKS5 proxy routing for web fetch |
| `amsha.tools.web-fetch.tor-socks-port` | `9050` | SOCKS5 proxy port for Tor network |
| `amsha.tools.web-fetch.tor-socks-host` | `127.0.0.1` | SOCKS5 proxy host for Tor network |
| `amsha.tools.github.repos-per-page` | `100` | GitHub API repos per page |
| `amsha.tools.host-exec.enabled` | `false` | Master toggle to enable host execution capability |
| `amsha.tools.host-exec.skills` | `""` | Comma-separated skill names granted host execution |
| `amsha.tools.host-exec.max-output-chars` | `8192` | Max output characters for host tool execution |
| `amsha.tools.host-exec.python-executable` | `(auto)` | Configured Python executable for host execution |
| `amsha.tools.host-exec.java-executable` | `(auto)` | Configured Java executable for host execution |
| `amsha.tools.host-exec.go-executable` | `(auto)` | Configured Go executable for host execution |
| `amsha.sandbox.max-output-chars` | `8192` | Max output characters for sandboxed tool execution |
| `amsha.sandbox.python-executable` | `(auto)` | Configured Python executable for sandboxed execution |
| `amsha.sandbox.java-executable` | `(auto)` | Configured Java executable for sandboxed execution |
| `amsha.sandbox.go-executable` | `(auto)` | Configured Go executable for sandboxed execution |
| `amsha.workspace.root-dir` | `./data/workspaces` | Root directory for workspace storage |
