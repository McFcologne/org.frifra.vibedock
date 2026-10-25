[VibeDock](README.md) › **AGENTS.md**

# VibeDock development and IntelliJ setup

This guide is for developers and coding agents working on this monorepo. It describes the existing setup and the configurations to create in IntelliJ IDEA. It does not authorize installation on a physical device.

## Project layout and working rules

| Path | Purpose |
| --- | --- |
| `vibedock-server/` | Java 21 / Maven / Spring Boot server, Docker image, administration and provider integration |
| `vibedock-client/` | Display firmware (VibeTV M1/ESP8266 and ESP32 boards, one PlatformIO environment per board), build scripts and shared IntelliJ build configuration |
| `vibedock-server/CLIENT-PROTOCOL.md` | Pairing (server invites), image pull, image format and device authentication |
| `vibedock-server/VALIDATION.md` | Existing validation evidence and outstanding checks |
| `VibeDock-Feasibility-Codex-Claude.md` | Provider feasibility findings and sources |
| `vibedock-client/FLASHING.md` | Manual full backup, flash verification and complete restore procedure |
| `RELEASING.md` | Releases, Docker registries (ghcr.io, Docker Hub), secrets and the repository settings the workflows rely on |
| `third-party/twemoji/` | Twemoji 17.0.3 SVGs for the server's emoji (CC BY 4.0, unmodified; origin, checksum and update steps in its README) |
| `vibedock-client/TLS.md` | https on the displays: TLS 1.3 with Mbed TLS on the M1, memory and stack layout, pitfalls, diagnostics, host test |

Keep project documentation in English. Never commit credentials, `.env`, login caches, runtime data, device backups or generated firmware. Do not print credentials in logs or terminal output. Preserve existing user changes.

Build and test configurations must compile or test only. Do not flash firmware, restore a backup, upload a filesystem, or write to a physical display without explicit authorization for that action. Leave existing original firmware backups untouched. Original-firmware redistribution rights have not been established; do not commit or distribute manufacturer firmware or device backups. Every user must create and verify their own full device backup before flashing, and restore only a matching backup from that same device. Client approval activates image delivery; it is separate from firmware installation.

Provider quota endpoints are internal interfaces, not guaranteed public APIs. Real corporate SSO access and automatic token renewal remain unproven. Do not invent authentication flows or bypass Entra policies. Devices receive images and their own device credentials, never provider tokens.

## Prerequisites on the Mac

- IntelliJ IDEA with Java and Maven support. Enable the **Shell scripts** plugin for the client build. Enable Docker support if available in your installation; otherwise use the IDE terminal for Docker Compose.
- A Java **21** JDK for local server builds and debugging. Docker supplies its own Java runtime, so a host JDK is not required for Docker-only operation.
- Maven **3.6.3 or newer**, or IntelliJ's bundled Maven. The repository currently has no Maven wrapper.
- Docker Desktop, running with a working Docker connection and Docker Compose.
- Python **3.10 or newer** for the client virtual environment and PlatformIO **6.2.0** (the ESP32-C6 build refuses older ones; the Python 3.9 of macOS is too old).
- A C++ compiler for the client policy test (`c++`, available with Apple's Command Line Tools).
- Network access for the first Maven, Docker and PlatformIO dependency downloads.

Terminal checks, where the corresponding tools are installed:

```sh
java -version
mvn -version
docker version
docker compose version
python3 --version
c++ --version
```

IntelliJ's configured JDK and Maven can differ from the terminal versions. Check both when a build fails. For command-line C++ tools missing on macOS, install Apple's Command Line Tools with `xcode-select --install`.

## Open the projects in IntelliJ

The recommended layout is two IntelliJ windows sharing one Git repository:

1. Use **File → Open** to open `vibedock-server/`, and import its `pom.xml` as a Maven project if prompted.
2. Open `vibedock-client/` in a **new window**.
3. Both directories belong to the Git repository at their common `vibedock/` parent. Use that repository as the VCS root if IntelliJ asks. Do not initialize nested Git repositories.

The saved client build configuration uses `$PROJECT_DIR$` and expects the client directory to be the open project. You may open the whole monorepo instead, but then import `vibedock-server/pom.xml` as Maven and adjust run-configuration paths to include the appropriate subdirectory. This repository has no parent Maven POM.

## Configure Java and Maven

In the server window:

1. Under **File → Project Structure → Project**, select a Java 21 SDK. Add or download a JDK if needed.
2. Use SDK default / Java 21 for the language level. The POM defines the Java version.
3. Under **Settings → Build, Execution, Deployment → Build Tools → Maven**, select bundled Maven or an installed compatible version.
4. Set the Maven importer and runner JDK to Java 21 where those options are available.
5. Reload the Maven project. Wait for dependency resolution before running builds.

Menu names vary slightly by IntelliJ version. The server uses Spring Boot 4.1.2. Its executable JAR is `target/vibedock-server.jar`.

### Run configuration: Verify VibeDock Server

Create **Run → Edit Configurations → + → Maven**:

| Field | Value |
| --- | --- |
| Name | `Verify VibeDock Server` |
| Working directory | `$PROJECT_DIR$` in the separately opened server project |
| Command line / goals | `verify` |
| JRE | Java 21 / project JDK |

Run it to compile, execute the test suite and package the JAR. Equivalent terminal command from `vibedock-server/`:

```sh
mvn verify
```

This configuration is a setup recipe, not a supplied XML file. It does not start the production server.

### Run configuration: Debug VibeDock Server locally

For Java debugging, create an **Application** configuration (or a Spring Boot configuration if available):

| Field | Value |
| --- | --- |
| Name | `Debug VibeDock Server` |
| Main class | `org.frifra.vibedock.server.Application` |
| Module / classpath | The imported server Maven module |
| Working directory | `$PROJECT_DIR$` in the server window |
| JRE | Java 21 |
| Environment | None needed: the server reads `.env` in the working directory (as Docker Compose does); optionally `POLLING_ENABLED=false` |
| Before launch | Build |

`ADMIN_USER`, `ADMIN_PASSWORD` and the other settings come from `.env` (see below); environment variables of the configuration take precedence. Do not save a password into a shared XML file. `POLLING_ENABLED=false` disables scheduled work for local development. Manual refresh and other UI write actions remain active. Use an empty local data directory and do not enable real devices for an isolated debugging session.

The local application uses `./data` by default, administration at `http://127.0.0.1:10001/admin`, and the public listener on port 10002. Stop a Docker instance using the same ports before starting it. Local Java operation does not inherit Docker's `/data` or its installed login clients. Browser login requires the compatible `codex` and `claude` executables to be available to the Java process; use Docker for the packaged provider sign-in setup.

## Start the server with Docker Compose

### Prepare local configuration

In the server terminal, create `.env` only if it does not already exist:

```sh
cp .env.example .env
```

Edit `.env` and set your own non-empty `ADMIN_PASSWORD`. Reuse an existing `.env` rather than overwriting it. The default username is `admin`. `.env` is ignored by Git.

### Connect IntelliJ to Docker Desktop

Under **Settings → Build, Execution, Deployment → Docker**, add or select the Docker Desktop connection. Confirm that the connection succeeds. The **Services** tool window can then show containers and logs.

If the build reports `docker-credential-desktop: executable file not found in $PATH`, the failure occurs before the Dockerfile stages run. Check Docker Desktop's CLI tool installation and whether the IDE can locate its credential helper. On this Mac the helper was found under Docker.app and `~/.docker/bin`. Keep the credential store enabled; changing image tags does not fix a missing helper. Restart the IDE after fixing the CLI installation if necessary.

### Run configuration: VibeDock Server — Compose

Create **Run → Edit Configurations → + → Docker → Docker Compose**, or use the run action on `compose.yaml`:

| Field | Value |
| --- | --- |
| Name | `VibeDock Server — Compose` |
| Docker server | The working Docker Desktop connection |
| Compose files | `$PROJECT_DIR$/compose.yaml` in the server window |
| Services | `vibedock` |
| Environment file | `$PROJECT_DIR$/.env` if configured explicitly; Compose uses the adjacent `.env` by default |
| Build | Enable rebuilding when Dockerfile or application changes must be included |

This is a recipe for a configuration to create locally. The service name is **`vibedock`**; the configured container name is **`vibedock-server`**. Select the Compose configuration when starting the service. A direct Dockerfile configuration does not apply Compose's container name, port mappings, environment or volume.

The equivalent terminal workflow from `vibedock-server/` is:

```sh
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 vibedock
```

Default deployment:

| Setting | Value |
| --- | --- |
| Administration | `http://127.0.0.1:10001/admin` on the Docker host |
| Admin port mapping | `${ADMIN_PUBLISH_ADDRESS:-0.0.0.0}:10001:10001` (network-wide by default) |
| Public API / device registration | Host port 10002 → container port 10002 |
| Device's configured server address | `http://<Docker-host-LAN-IP>:10002` |
| Persistent data | Host directory `vibedock-server/docker.data/` mounted at `/data` |

An ESP8266 must use the host's LAN/VPN address, not `127.0.0.1`. The server also needs a route back to the device's registration source IP to push images. The data directory is documented in the [server README](vibedock-server/README.md).

### If using a direct Dockerfile run configuration

Configure all of these explicitly: container name `vibedock-server`, `ADMIN_USER`, `ADMIN_PASSWORD`, **`ADMIN_BIND_ADDRESS=0.0.0.0`**, both required port mappings, and a bind mount of `vibedock-server/docker.data` at `/data`. Administration is published network-wide by default and uses plain HTTP; use a strong password, or bind the host side to `127.0.0.1` for host-only access. A Dockerfile `EXPOSE` declaration does not publish a host port.

A running container with no published ports cannot be reached at localhost. Changing mappings requires container recreation. Preserve any data stored only inside an existing container before replacing it. Do not delete `docker.data/` as routine cleanup: it holds the settings and saved logins.

## Set up and build the client

In the separately opened client project's terminal:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install "platformio==6.2.0"
```

Reuse `.venv` if it already exists. Select **Build VibeDock Client** in IntelliJ's run dropdown and click **Run**. The supplied file is:

```text
vibedock-client/.run/Build VibeDock Client.run.xml
```

It executes `scripts/build.sh` with `/bin/sh`. That script compiles the `vibedock-m1` environment (another board as argument, `all` for every board) and never uploads firmware. If the configuration is missing, enable **Shell scripts**, reopen the client project, or create the equivalent **Shell Script** configuration:

| Field | Value |
| --- | --- |
| Name | `Build VibeDock Client` |
| Script path | `$PROJECT_DIR$/scripts/build.sh` |
| Interpreter | `/bin/sh` |
| Working directory | `$PROJECT_DIR$` |
| Script/interpreter options | Empty |
| Before launch | No firmware upload or device action |

Terminal equivalent:

```sh
./scripts/build.sh
```

A successful build produces `firmware/.pio/build/vibedock-m1/firmware.bin`. The first build downloads the toolchain and dependencies. Every build also writes `flash.bin`, the image installed at `0x0` over USB. The pinned ESP8266 and ESP32 platforms, libraries and per-board display configuration are defined in `firmware/platformio.ini`; `scripts/images.sh` builds all boards as the Docker image does.

When the whole monorepo is open, use `$PROJECT_DIR$/vibedock-client/scripts/build.sh` and `$PROJECT_DIR$/vibedock-client` as the script and working directory. Do not assume that a configuration saved for the separate client project has the same project root.

### Optional configuration: Test VibeDock Client Policy

Create a **Shell Script** configuration using script text, `/bin/sh`, and the client project as working directory:

```sh
c++ -std=c++17 tests/policy_test.cpp -o /tmp/vibedock-policy-test && /tmp/vibedock-policy-test
```

This native test checks timing, rollover, pixel decoding and the colour correction without accessing hardware. It exits with code 0 and no output on success. It is a setup recipe, not a supplied configuration file.

## Share configurations without sharing secrets

IntelliJ's **Store as project file** option allows credential-free configurations to be saved in `.run/*.run.xml`. Supplied are the client build configuration and, in `vibedock-server/.run/`, the server configurations: Spring Boot, Docker Compose, a quick Compose build without the manual, a test server with made-up usage and no sign-in that takes over the normal container and its data, demo servers and the multi-platform image build. The verify and policy-test configurations above are instructions to create them.

Keep machine-specific SDK paths, Docker connection details and passwords local. Before committing a run configuration, review its XML for credentials. The client ignore file explicitly allows its shared run XML even when a global Git ignore rule excludes `.run`. Generated `.venv`, `.pio`, runtime data and device backups remain ignored.

## First-run checklist

1. Import the server in IntelliJ and run `Verify VibeDock Server`.
2. Create or reuse the server `.env`, set its password, and start the **Compose** configuration.
3. Confirm the expected container name and published ports in Services or `docker compose ps`. Open the running administration URL, rather than the `admin.html` source file.
4. Use the Codex and Claude sign-in buttons to complete the permitted corporate browser sign-in. Follow any displayed device or return-code instructions. Never paste an Entra token as a provider token.
5. Inspect provider status. Saved credentials persist in `/data`; persistence does not automatically renew expired tokens. Missing or failed quota data must remain unavailable, not appear as zero usage.
6. Install PlatformIO for the client, build it, and run the native policy test.
7. Before any physical installation, follow [FLASHING.md](vibedock-client/FLASHING.md) to prepare a complete verified backup and obtain explicit approval for flashing. No run configuration performs this step.
8. After approved installation, use the device's displayed setup network/password and `http://192.168.4.1` portal to enter the Wi-Fi settings.
9. Pair the device in administration (*Devices › Pair device*) with the IP and pairing code shown on its screen. Confirm the locally drawn IP remains visible at the bottom.

The usage JSON IP allowlist starts empty. It controls read access to `/api/v1/usage`, independently of device pairing and image pulls. Allow individual source IPs or ranges only when JSON access is needed.

## Common problems

| Symptom | Check |
| --- | --- |
| Container receives a random name | The Dockerfile was run directly; select Compose or set the direct configuration's container name. |
| Container runs but admin is unreachable | Published host port 10001, container admin bind address, and correct URL; the public port does not serve administration. |
| Startup fails with missing password | `.env` and Compose environment, or the direct run configuration's runtime environment. |
| Port already in use | Stop the conflicting development instance; only one process can own the same host address and port. |
| SSO buttons fail in a static preview | Open the running server's authenticated administration URL. |
| Client build configuration is absent | Open the client folder separately and enable Shell scripts; check the configuration path. |
| PlatformIO is missing | Install it in the client's `.venv`; the script does not require global `pio`. |
| Device paired but shows no images | Announced server addresses (internal/external), device reachability of port 10002, poll status in administration. |
| Client quotas vanish after an outage | Expected stale-image protection; inspect server connectivity and provider status. |

## References

- [IntelliJ Docker integration](https://www.jetbrains.com/help/idea/docker.html)
- [IntelliJ Docker Compose workflow](https://www.jetbrains.com/help/idea/docker-compose.html)
- [Docker Compose run configuration fields](https://www.jetbrains.com/help/idea/docker-compose-run-configuration.html)
- [Sharing IntelliJ run configurations](https://www.jetbrains.com/help/idea/run-debug-configuration.html)
- [Detailed server setup](vibedock-server/README.md)
- [Detailed client setup](vibedock-client/README.md)
