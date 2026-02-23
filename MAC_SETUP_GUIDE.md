brew install podman

podman machine init
podman machine start


Starting machine "podman-machine-default"

This machine is currently configured in rootless mode. If your containers
require root permissions (e.g. ports < 1024), or if you run into compatibility
issues with non-podman clients, you can switch using the following command:

	podman machine set --rootful

API forwarding listening on: /var/folders/p2/9x91kvtj38x2km_fffb62hf40000gn/T/podman/podman-machine-default-api.sock

The system helper service is not installed; the default Docker API socket
address can't be used by podman. If you would like to install it, run the following commands:

        sudo /opt/homebrew/Cellar/podman/5.8.0/bin/podman-mac-helper install
        podman machine stop; podman machine start

You can still connect Docker API clients by setting DOCKER_HOST using the
following command in your terminal session:

        export DOCKER_HOST='unix:///var/folders/p2/9x91kvtj38x2km_fffb62hf40000gn/T/podman/podman-machine-default-api.sock'

Machine "podman-machine-default" started successfully


----

/usr/libexec/java_home -V

jenv add /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home

jenv global 25.0