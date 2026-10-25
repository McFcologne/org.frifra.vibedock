[VibeDock](README.md) › **Security**

# Security policy

## Supported versions

Only the latest release gets security fixes. Displays get their firmware from the server they are paired with, so
updating the server image and then the displays over Wi-Fi brings both to the fixed version. Older releases are not
patched; a fix comes as a new release (see [RELEASING.md](RELEASING.md)).

## Reporting a vulnerability

Please do **not** open a public issue, discussion or pull request for a security problem.

Use **Report a vulnerability** in the repository's
[Security tab](https://github.com/McFcologne/org.frifra.vibedock/security). The report stays private between you and
the maintainer until a fix is published. Please include:

- the affected version (administration footer, or the firmware version on the device card) and the board,
- what an attacker needs (network position, credentials, physical access) and what they gain,
- steps to reproduce, ideally with a minimal setup, and logs without tokens or passwords.

VibeDock is maintained in spare time. You get a first answer within about a week. Once confirmed, the fix is
published as a new release together with a GitHub security advisory; you are credited there unless you prefer not
to be. Please give us a reasonable time to release the fix before you disclose details.

## Scope

In scope:

- the server and its administration (sign-in, sessions, CSRF protection, access to the administration and public
  ports),
- the device protocol: pairing, device keys, image pulls, `POST /pull` callbacks and web display links
  ([CLIENT-PROTOCOL.md](vibedock-server/CLIENT-PROTOCOL.md)),
- the firmware, its TLS pinning ([TLS.md](vibedock-client/TLS.md)) and the signature check of updates over Wi-Fi,
- how credentials are stored in the data directory and kept out of logs, exports and device images,
- the Docker image and the release workflow.

Out of scope:

- vulnerabilities in the providers' services or in the official provider clients themselves; report those to the
  provider,
- an attacker who already has the complete data directory, a shell on the host or physical access to a display,
- the documented trade-offs, as long as VibeDock behaves as described: plain HTTP for the administration unless you
  put HTTPS in front of it, unencrypted HTTP pairing on a trusted network, `ADMIN_AUTH=none` for local tests,
- the original firmware of third-party devices and setups that deviate from the documentation.

## Security model in short

- Provider tokens and keys stay on the server; their fields are encrypted in the data directory with the private
  `.installation-id` ([server README](vibedock-server/README.md)). Displays receive only their own device key.
- The administration password is stored as a BCrypt hash. Keep the administration port in a trusted network or behind
  HTTPS.
- Updates over Wi-Fi are signed with the server's key (`ota-signing.key` in the data directory); displays accept only
  firmware signed by the server they are paired with.
- Over HTTPS, displays check the server certificate against the pin and the root from pairing.
- The JSON usage API on the public port stays blocked until readers are allowed explicitly.
