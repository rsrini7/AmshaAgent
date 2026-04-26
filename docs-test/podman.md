
**Check installed packages (Alpine apk database):**
```bash
podman run --rm arcclaw-tool-runner:latest apk info | sort
```

**Check image history - shows every layer and the command that created it:**
```bash
podman history arcclaw-tool-runner:latest
```

**Check image creation date:**
```bash
podman images arcclaw-tool-runner --format "{{.CreatedAt}}"
```
