# WSL2 Setup Guide (Ubuntu)

This guide provides a comprehensive walkthrough for setting up a native development environment inside WSL2 Ubuntu, including JDK 25 and the Podman engine for sandbox isolation.

---

## ☕ Phase 1: Install JDK 25 (via SDKMAN!)

Using SDKMAN! is the easiest way to manage Java versions. It handles downloading, extracting, and path configuration automatically.

1. **Install SDKMAN!**:
   Run this in your WSL2 Ubuntu terminal:
   ```bash
   curl -s "https://get.sdkman.io" | bash
   ```

2. **Initialise SDKMAN!**:
   To start using it immediately without restarting your terminal:
   ```bash
   source "$HOME/.sdkman/bin/sdkman-init.sh"
   ```

3. **Install JDK 25**:
   Tell SDKMAN! to install version 25.
   ```bash
   # For Oracle JDK
   sdk install java 25-oracle
   # OR for OpenJDK (Temurin)
   # sdk install java 25-open
   ```

4. **Verify**:
   Check that everything is linked correctly:
   ```bash
   java -version
   ```

---

## 🐳 Phase 2: Native Podman Setup

Setting up a native Podman engine inside WSL2 ensures ArcClaw can leverage Tier 2 Sandbox isolation correctly.

### 1. Enable Systemd (The Foundation)
Podman requires systemd to manage its API socket.

1. **Edit WSL Configuration**:
   ```bash
   sudo nano /etc/wsl.conf
   ```

2. **Add these lines**:
   ```ini
   [boot]
   systemd=true
   ```

3. **Save and Exit**: Press `Ctrl+O`, `Enter`, then `Ctrl+X`.

4. **Restart WSL (REQUIRED)**: Close your Ubuntu terminal, open PowerShell on Windows, and run:
   ```powershell
   wsl --shutdown
   ```

### 2. Install Podman & Docker CLI
Re-open your Ubuntu terminal and install the tools.

1. **Install Podman**:
   ```bash
   sudo apt update
   sudo apt install podman -y
   ```

2. **Install Docker CLI Only (Optional)**:
   If you want to use the `docker` command to interact with Podman:
   ```bash
   # Add Docker's GPG key
   sudo install -m 0755 -d /etc/apt/keyrings
   curl -fsSL https://download.docker.com | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
   sudo chmod a+r /etc/apt/keyrings/docker.gpg

   # Add the repository
   echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

   # Install ONLY the CLI
   sudo apt update
   sudo apt install docker-ce-cli -y
   ```

### 3. Setup the Podman Socket
This creates the "bridge" that allows the Docker CLI (or ArcClaw) to talk to Podman.

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

### 4. Configure Environment Variables
Tell the Docker CLI where to find the Podman socket.

1. **Add to your Bash Profile**:
   ```bash
   echo 'export DOCKER_HOST="unix:///run/user/1000/podman/podman.sock"' >> ~/.bashrc
   source ~/.bashrc
   ```

### 5. Final Test
```bash
# If using Podman directly
podman ps
# If using Docker CLI
docker ps
```

---

## 🛠️ Troubleshooting
If you receive a permission error with `docker ps`, ensure your user is in the correct group:
```bash
sudo usermod -aG $(getent group 10 | cut -d: -f1) $USER
```
