[VibeDock](../README.md) › [Server](README.md) › **Dependency upgrades**

# Server dependency upgrades (0.3.0)

Spring Boot 3.5.16 is replaced by 4.1.2, retaining Java 21. Its managed stack includes Jackson 3.1.4,
Spring Framework 7 and the corresponding Spring Security and embedded Tomcat versions. Web MVC and its
MockMvc test support now use the dedicated Boot 4 starters. Tomcat connector customisation uses the new package/API.

Jackson imports move to `tools.jackson`; annotations remain in `com.fasterxml.jackson.annotation`. Mappers are
built before use; tree property iteration and text nodes use the Jackson 3 API. JSON parsing exceptions are now
unchecked: damaged export/support files and unreadable diagnostic history retain their existing error handling.
`JsonCompatibility` disables `FAIL_ON_NULL_FOR_PRIMITIVES` because existing JSON intentionally omits false/zero
fields. The same configuration is applied to Spring's mapper and direct mappers in tests; strict trailing-token
validation for encrypted credential fields remains enabled.

## Compatibility and rollback

No settings schema version, AES-GCM envelope, key derivation, credential field prefix, password hash or device
protocol version is changed by this dependency upgrade. Existing migration/restart tests cover prior registry
formats, credentials, exports and layouts. The appearance feature separately adds an optional `dark` field to
`themes.json` and an opt-in device protocol header (see [client protocol](CLIENT-PROTOCOL.md)).

Before production rollout, keep a complete private backup of the data directory and the previous server image.
Stop the server before restoring that matching data backup for a rollback; do not allow two versions to write
one data directory. No production data or running server is changed by the build/test process.

## Validation

Java 21 Maven verification passes **236 tests with zero failures/errors/skips** (measured again for 0.3.0 with the
transfer changes), including the existing file-format and migration tests and the new UI persistence/header/callback
checks. Detailed validation is recorded in [VALIDATION.md](VALIDATION.md). Provider sign-in with real accounts and the complete
Docker image/manual build still require an integration run; Maven verification alone does not establish these.

References: [Spring Boot migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide),
[Jackson 3 changes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0).
