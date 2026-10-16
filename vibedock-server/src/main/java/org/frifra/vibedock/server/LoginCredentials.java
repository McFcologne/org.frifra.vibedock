package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.IOException;

/** Reads only this service's CLI login caches, never the host's home or Keychain. */
final class LoginCredentials {
    private final Path directory;
    private final ObjectMapper json;
    LoginCredentials(Path directory,ObjectMapper json) { this.directory=directory; this.json=json; }

    SettingsStore.Credential read(SettingsStore.Account account,SettingsStore.Credential manual) {
        if (!manual.token().isBlank()) return manual;
        String provider=account.type();
        if (!SettingsStore.Account.SIGN_IN_TYPES.contains(provider)) return manual;
        Path file=SettingsStore.authDirectory(directory,account).resolve(account.credentialFile());
        if (provider.equals("grok")) return grok(file,json);
        if (provider.equals("gemini")) return gemini(file,json);
        try {
            if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) return empty();
            byte[] bytes;
            try (var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { bytes=input.readNBytes(65537); }
            if (bytes.length>65536) return empty();
            var root=json.readTree(bytes);
            var node=root.path(provider.equals("codex") ? "tokens" : "claudeAiOauth");
            var token=node.path(provider.equals("codex") ? "access_token" : "accessToken");
            var accountNode=node.path("account_id");
            if (!token.isTextual() || token.textValue().isBlank() || token.textValue().length()>16384
                    || token.textValue().contains("\r") || token.textValue().contains("\n")) return empty();
            String id=provider.equals("codex") && accountNode.isTextual() ? accountNode.textValue() : "";
            if (id.length()>200 || id.contains("\r") || id.contains("\n")) return empty();
            return new SettingsStore.Credential(token.textValue(),id);
        } catch (IOException | RuntimeException ignored) {
            // A partially written, removed or invalid cache never reuses an old token.
            return empty();
        }
    }
    private static SettingsStore.Credential empty() { return new SettingsStore.Credential("",""); }
    /**
     * Grok Build keeps its sign-in in $GROK_HOME/auth.json: entries with the bearer token in "key", the issuer and
     * "expires_at". The sign-in entry of auth.x.ai is used; API-key entries are not a subscription sign-in.
     */
    static SettingsStore.Credential grok(Path file,ObjectMapper json) {
        try {
            if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) return empty();
            byte[] bytes;
            try (var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { bytes=input.readNBytes(65537); }
            if (bytes.length>65536) return empty();
            var entries=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();collect(json.readTree(bytes),entries,0);
            var best=entries.stream().filter(e->e.toString().contains("auth.x.ai")).findFirst().or(()->entries.stream().findFirst());
            if (best.isEmpty()) return empty();
            String token=best.get().path("key").textValue();
            if (token.length()>16384 || token.contains("\r") || token.contains("\n")) return empty();
            long expires=SignInRenewal.epochSeconds(best.get().path("expires_at"));
            if (expires>0 && expires<=java.time.Instant.now().getEpochSecond()) return empty();
            return new SettingsStore.Credential(token,"");
        } catch (IOException | RuntimeException ignored) { return empty(); }
    }
    /**
     * Gemini CLI keeps its Google sign-in in $GEMINI_CLI_HOME/.gemini/oauth_creds.json: "access_token" and
     * "expiry_date" in milliseconds. An expired token is not used; the CLI renews it (see SignInRenewal).
     */
    static SettingsStore.Credential gemini(Path file,ObjectMapper json) {
        try {
            if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) return empty();
            byte[] bytes;
            try (var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { bytes=input.readNBytes(65537); }
            if (bytes.length>65536) return empty();
            var root=json.readTree(bytes);var token=root.path("access_token");
            if (!token.isTextual() || token.textValue().isBlank() || token.textValue().length()>16384
                    || token.textValue().contains("\r") || token.textValue().contains("\n")) return empty();
            long expires=SignInRenewal.epochSeconds(root.path("expiry_date"));
            if (expires>0 && expires<=java.time.Instant.now().getEpochSecond()) return empty();
            return new SettingsStore.Credential(token.textValue(),"");
        } catch (IOException | RuntimeException ignored) { return empty(); }
    }
    private static void collect(com.fasterxml.jackson.databind.JsonNode node,java.util.List<com.fasterxml.jackson.databind.JsonNode> out,int depth) {
        if (depth>4) return;
        if (node.isObject() && node.path("key").isTextual() && !node.path("key").textValue().isBlank()
                && !node.path("key").textValue().startsWith("xai-")) out.add(node);
        node.forEach(child->collect(child,out,depth+1));
    }
}
