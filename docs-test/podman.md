
**Check installed packages (Alpine apk database):**
```bash
podman run --rm amsha-tool-runner:latest apk info | sort
```

**Check image history - shows every layer and the command that created it:**
```bash
podman history amsha-tool-runner:latest
```

**Check image creation date:**
```bash
podman images amsha-tool-runner --format "{{.CreatedAt}}"
```
