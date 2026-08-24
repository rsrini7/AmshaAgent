# Amsha Sandbox Module (`amsha-sandbox`)

This module provides OS-level process isolation, Podman rootless container execution, Linux namespace isolation, and sandbox security controls for the Amsha Agent framework.

## Configuration Properties

| Property | Default | Description |
|---|---|---|
| `amsha.sandbox.enabled` | `true` | Master toggle to enable or disable sandboxed container execution |
| `amsha.sandbox.strategy` | `PODMAN` | Sandbox isolation strategy (`PODMAN`, `NAMESPACE`, `DIRECT`, `NONE`) |
| `amsha.sandbox.image` | `(unset)` | High-priority container image override for tool execution (takes precedence over `podman-image`) |
| `amsha.sandbox.podman-image` | `localhost/amsha-tool-runner:latest` | Podman container image for tool execution |
| `amsha.sandbox.podman-path` | `podman` | Executable path to Podman CLI |
| `amsha.sandbox.network` | `bridge` | Podman container network mode (`bridge`, `none`, `host`) |
| `amsha.sandbox.cpu-limit` | `0.5` | Container CPU cores limit |
| `amsha.sandbox.memory-limit` | `512m` | Container memory limit |
| `amsha.sandbox.timeout-seconds` | `30` | Execution timeout in seconds |
| `amsha.sandbox.network-timeout-seconds` | `60` | Network execution timeout in seconds |
| `amsha.sandbox.allow-insecure-fallback` | `false` | Allow direct host execution fallback on container failure |
| `amsha.sandbox.tool-runner-user` | `sandbox` | Unprivileged container user |
| `amsha.sandbox.circuit-breaker.failure-threshold` | `3` | Number of consecutive failures before opening the circuit breaker |
| `amsha.sandbox.circuit-breaker.reset-timeout-seconds` | `30` | Cool-down period in seconds before half-opening the circuit breaker |
| `amsha.workspace.root-dir` | `./data/workspaces` | Base directory for session workspaces |

### Image Resolution

Container image resolution (`SandboxImageResolver`) applies the following precedence:

1. `AMSHA_SANDBOX_IMAGE` (environment / system property)
2. `amsha.sandbox.image` (configuration property)
3. `AMSHA_SANDBOX_PODMAN_IMAGE` (environment / system property)
4. `amsha.sandbox.podman-image` (configuration property)
5. Default `localhost/amsha-tool-runner:latest`

Blank values are skipped, so an unset or empty variable falls through to the next level.
