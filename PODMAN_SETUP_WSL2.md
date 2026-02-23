# Podman Setup Guide (Native WSL2 Ubuntu)

This guide provides a step-by-step walkthrough for setting up a native Podman engine inside WSL2 Ubuntu and connecting a Docker CLI-only client to it. This configuration is required for ArcClaw's Tier 2 Sandbox isolation when developing on Windows.

## Phase 1: Enable Systemd (The Foundation)
Podman requires systemd to manage the API socket that the Docker CLI talks to.

1. **Edit WSL Configuration**:
   ```bash
   sudo nano /etc/wsl.conf
   ```

2. **Paste these lines into the file**:
   ```ini
   [boot]
   systemd=true
   ```

3. **Save and Exit**: Press `Ctrl+O`, `Enter`, then `Ctrl+X`.

4. **Restart WSL (REQUIRED)**: Close your Ubuntu terminal, open PowerShell on Windows, and run:
   ```powershell
   wsl --shutdown
   ```

## Phase 2: Install Podman & Docker CLI
Re-open your Ubuntu terminal and install the tools.

1. **Install Podman**:
   ```bash
   sudo apt update
   sudo apt install podman -y
   ```

2. **Install Docker CLI Only**:
   ```bash
   # Add Docker's GPG key
   sudo install -m 0755 -d /etc/apt/keyrings
   curl -fsSL https://download.docker.com | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
   sudo chmod a+r /etc/apt/keyrings/docker.gpg

   # Add the repository
   echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

   # Install ONLY the CLI (not the engine)
   sudo apt update
   sudo apt install docker-ce-cli -y
   ```

## Phase 3: Setup the Podman Socket
This creates the "bridge" that allows the Docker CLI to talk to Podman.

1. **Enable the User-level Socket**:
   ```bash
   systemctl --user enable --now podman.socket
   ```

2. **Verify the socket exists**:
   ```bash
   ls -l /run/user/1000/podman/podman.sock
   ```
   > [!NOTE]
   > If your User ID is not 1000, run `id -u` to find yours and adjust the path in the next step.

## Phase 4: Configure Environment Variables
Tell the Docker CLI where to find the Podman socket automatically.

1. **Add to your Bash Profile**:
   ```bash
   echo 'export DOCKER_HOST="unix:///run/user/1000/podman/podman.sock"' >> ~/.bashrc
   source ~/.bashrc
   ```

## Phase 5: Final Test
Everything is now linked. You can use standard Docker commands.

1. **Test the connection**:
   ```bash
   docker ps
   ```

## Troubleshooting Tip
If `docker ps` gives a permission error, ensure your user is in the correct group (though user-level sockets usually don't require this):
```bash
sudo usermod -aG $(getent group 10 | cut -d: -f1) $USER
```
