TamboUI - The Modern Alternative
A far better fit for Quarkus + TUI in 2026 is TamboUI, announced in February 2026 and co-created by Max Andersen from the Quarkus team and Cédric Champeau. Key advantages over Lanterna:

First-class GraalVM native support - sub-100ms startup, competitive with Go/Rust
Multiple backends - JLine, Aesh, Panama
PicoCLI integration built-in
Immediate-mode rendering + CSS layout - declarative Toolkit DSL handles the event loop for you
Multi-level API - from low-level widgets up to a full declarative toolkit
Works on Java 8+, most ergonomic on modern Java
TamboUI is essentially Java's answer to Rust's Ratatui or Python's Textual. Given your ArcClaw project uses GraalVM native builds already, TamboUI is the strongly recommended path.

There's also an open GitHub issue [#52750] on Quarkus requesting a native interactive TUI mode directly in quarkus:dev, so first-party TUI support may land in Quarkus itself in the near future.

The answer is clear: **use TamboUI alongside PicoCLI, not as a replacement**. TamboUI even ships a dedicated `tamboui-picocli` optional integration module, making the two explicitly designed as complementary layers. [tamboui](https://tamboui.dev/docs/main/)

***

## Why They're Different Layers

PicoCLI and TamboUI solve different problems at different levels of the stack - they don't compete:

| Concern | Tool | ArcClaw Role |
|---|---|---|
| **Argument parsing** | PicoCLI | `--agent`, `--dry-run`, `--env`, subcommands |
| **Help/version text** | PicoCLI | `--help`, `--version` auto-generated |
| **Interactive TUI rendering** | TamboUI | Agent dashboards, HITL prompts, live logs |
| **Event loop / key bindings** | TamboUI | `q` to quit, arrow nav, form inputs |
| **CDI + Quarkus wiring** | `quarkus-picocli` | DI injection, lifecycle, native mode |

PicoCLI parses what the user typed before TamboUI starts rendering. Once the command is identified, TamboUI takes over the screen. [quarkus](https://quarkus.io/guides/picocli)

***

## Integration Architecture for ArcClaw

The pattern is: **PicoCLI routes to a command → command launches TamboUI** using the `tamboui-picocli` module.

```xml
<!-- pom.xml additions -->
<dependency>
  <groupId>dev.tamboui</groupId>
  <artifactId>tamboui-toolkit</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>dev.tamboui</groupId>
  <artifactId>tamboui-picocli</artifactId>       <!-- optional integration -->
  <version>0.2.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>dev.tamboui</groupId>
  <artifactId>tamboui-jline3-backend</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```

```java
// Quarkus PicoCLI entrypoint - unchanged
@QuarkusMain
@CommandLine.Command(name = "arcclaw", mixinStandardHelpOptions = true,
    subcommands = { MonitorCommand.class, ApproveCommand.class, RunCommand.class })
public class ArcClawCli implements Runnable, QuarkusApplication {
    @Inject CommandLine.IFactory factory;

    @Override public void run() { /* default: show help */ }

    @Override
    public int run(String... args) throws Exception {
        return new CommandLine(this, factory).execute(args);
    }
}
```

```java
// TamboUI kicks in INSIDE a picocli subcommand
@CommandLine.Command(name = "monitor", description = "Live agent monitoring dashboard")
@ApplicationScoped
public class MonitorCommand implements Runnable {

    @Inject AgentSchedulerService scheduler;
    @Inject ToolExecutionLog toolLog;

    @Override
    public void run() {
        new ArcClawDashboard(scheduler, toolLog).run(); // TamboUI ToolkitApp
    }
}
```

```java
// TamboUI dashboard - full interactive TUI
import static dev.tamboui.toolkit.Toolkit.*;
import dev.tamboui.toolkit.app.ToolkitApp;

public class ArcClawDashboard extends ToolkitApp {
    private final AgentSchedulerService scheduler;
    private final ToolExecutionLog log;

    @Override
    protected Element render() {
        return dock(
            panel("ArcClaw - Agent Monitor",
                table(agentStatusRows()).headers("Agent", "Status", "Next Run", "Last Tool"),
                spacer(),
                paragraph(log.tail(10)).dim()
            ).rounded(),
            panel("HITL Queue",
                list(pendingApprovals()).onSelect(this::handleApproval)
            ).width(30).right()
        );
    }
}
```

***

## ArcClaw-Specific TUI Screens

Given ArcClaw's architecture (agents, HITL approvals, scheduling, multi-LLM), here's what maps naturally to TamboUI's widget library: [byteiota](https://byteiota.com/tamboui-modern-java-terminal-ui-with-graalvm-native/)

- **`arcclaw monitor`** → Full-screen agent status table (TamboUI `Table` widget) + live tool execution log (`Paragraph` with tail scroll)
- **`arcclaw approve`** → HITL interactive approval queue - list with `[A]pprove / [R]eject / [V]iew` key bindings via `@OnAction`
- **`arcclaw schedule`** → Cron schedule viewer with `Gauge` widgets showing last/next execution per agent
- **`arcclaw run --agent X --interactive`** → PicoCLI flags parsed first, then TamboUI inline progress display (`Inline Toolkit` mode - npm-style progress, no full-screen takeover)
- **`arcclaw config`** → TamboUI `Form` DSL with validation for configuring LLM endpoints, Telegram tokens, DB paths

***

## Native Image Consideration

Since ArcClaw already targets GraalVM native, TamboUI's first-class native support is the key differentiator here. The `tamboui-picocli` module ships its own GraalVM metadata so you don't need to write manual `reflect-config.json` entries for the TUI layer. The startup target you can expect: **< 100ms** - same as your Quarkus native CLI binary today. [tamboui](https://tamboui.dev/docs/main/)

The only caveat: TamboUI is currently on **snapshot builds** (0.2.0-SNAPSHOT via OSSRH) with APIs marked as potentially unstable. For production ArcClaw use, pin to a specific commit hash or wait for a stable release before building core HITL flows on it. [byteiota](https://byteiota.com/tamboui-modern-java-terminal-ui-with-graalvm-native/)