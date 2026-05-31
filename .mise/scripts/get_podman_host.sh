#!/bin/bash
# Detect Podman socket and output as DOCKER_HOST for Docker compatibility

if command -v podman &> /dev/null; then
    # Check if Podman machine is running (macOS)
    if podman machine list 2>/dev/null | grep -q "Running"; then
        # Get the SSH URI from podman system connection
        URI=$(podman system connection list 2>/dev/null | grep "podman-machine-default" | awk '{print $2}')
        if [ -n "$URI" ]; then
            echo "$URI"
            exit 0
        fi
    fi
    
    # Linux/WSL2: Check for Unix socket
    MYUID=$(id -u 2>/dev/null)
    SOCK="/run/user/$MYUID/podman/podman.sock"
    if [ -S "$SOCK" ]; then
        echo "unix://$SOCK"
        exit 0
    fi
fi

# Fallback: no Podman found
exit 0
