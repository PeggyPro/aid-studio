# Container execution adapter

`src/container-isolation.mjs` is an operator-selected private execution adapter for hosts whose service manager cannot enforce the native adapter's resource properties. It does not disable the native adapter or fall back to unrestricted execution.

Build `Dockerfile.runtime` from an explicitly pinned official Ubuntu image digest. The resulting local image ID must be configured as `containerIsolation.image` (`sha256:<64 hex>`); every task uses `--pull never`. Mount the same reviewed Node/Blender runtime directories read-only into the controller and task containers. Do not install or replace the host glibc.

The trusted controller needs the local Unix Docker socket. Generated task containers never receive that socket, credentials, controller state, host root, host networking, or writable host directories. The controller must run with the configured non-root UID; only its supplementary group can access the daemon socket. Do not expose the Docker API over TCP.

Budget controller threads separately from each task's PID limit: a 14-slot node uses a controller PID limit of 1024 with 2 CPUs and 2 GiB memory. Docker CLI subprocesses use `GOMAXPROCS=2` so their schedulers do not scale to every host CPU. Check the controller's `pids.events` during concurrent acceptance; quota failures are not successful cancellation receipts.

`containerIsolation` carries `uid`, `gid`, `image`, `cpuMillis`, `memoryBytes`, `scratchBytes`, `tasksMax`, `runtimeRoots`, `executables`, and the same allowlisted runtime `environments` as the native adapter. `executorModule` and `executorSha256` must point to and pin this adapter. Existing signed protocol, node identity, TLS, durable managed configuration, per-task reservations, and job ledger remain unchanged.

Every task has its own network and process namespaces, read-only root, dropped capabilities, no-new-privileges, PID limit, CPU quota, memory/swap limit and bounded tmpfs. A trusted supervisor streams bounded, path-validated outputs; generated code cannot reopen its export FD via procfs. Its independent deadline also terminates execution if the controller disappears. Cancellation and timeout force-remove the container and verify absence before releasing the execution boundary. An unavailable daemon cannot be treated as proof that termination succeeded.

Before enabling a restored node, verify real source builds and previews, unreachable host loopback/credentials/socket, enforced tmpfs limits, memory OOM classification, timeout/cancel cleanup, configuration restoration and admission/queue limits. A successful daemon startup or declared HostConfig alone is not complete acceptance. This adapter currently supports Three.js/Blender builds and Blender previews; it does not advertise document/resource extraction.

No SQL changes are required. Older host distributions still require a compatible maintained kernel and container runtime; a modern image alone does not update the host kernel.
