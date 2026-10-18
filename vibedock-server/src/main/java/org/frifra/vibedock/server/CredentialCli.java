package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;

/** Container login helper; official CLIs see RAM files, never the persistent encrypted credential files. */
public final class CredentialCli {
    private CredentialCli() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=1||!Set.of("codex","claude","codex-logout","claude-logout").contains(args[0]))
            throw new IllegalArgumentException("Expected codex, claude, codex-logout or claude-logout");
        boolean logout=args[0].endsWith("-logout");String type=args[0].replace("-logout","");
        String directory=System.getenv().getOrDefault("DATA_DIR","/data"),runtime=System.getenv().getOrDefault("AUTH_RUNTIME_DIR","");
        var json=new ObjectMapper();var store=new SettingsStore(directory,json,new SecretStore(Path.of(directory),"",runtime));
        var account=store.get().accounts().stream().filter(a->a.type().equals(type)).findFirst().orElseThrow(()->new IllegalArgumentException("Create a "+type+" data source first"));
        List<String> command=type.equals("codex")?logout?List.of("codex","-c","cli_auth_credentials_store=\"file\"","logout")
                :List.of("codex","-c","cli_auth_credentials_store=\"file\"","login","--device-auth")
                :logout?List.of("claude","auth","logout"):List.of("claude","auth","login","--claudeai","--sso");
        int exit;
        try(var workspace=store.secrets.workspace(store.authDirectory(account))){
            var builder=new ProcessBuilder(command).directory(workspace.path.toFile()).inheritIO();var env=builder.environment();
            for(String key:List.of("OPENAI_API_KEY","CODEX_API_KEY","CODEX_ACCESS_TOKEN","CODEX_AUTH_JSON","ANTHROPIC_API_KEY","ANTHROPIC_AUTH_TOKEN","CLAUDE_CODE_OAUTH_TOKEN","ANTHROPIC_PROFILE",
                    "CLAUDE_CODE_USE_BEDROCK","CLAUDE_CODE_USE_VERTEX","CLAUDE_CODE_USE_FOUNDRY"))env.remove(key);
            for(String key:List.of("HOME","CODEX_HOME","CLAUDE_CONFIG_DIR"))env.put(key,workspace.path.toString());
            var process=builder.start();
            try{exit=process.waitFor();}finally{if(process.isAlive()){process.destroyForcibly();process.waitFor();}}
        }
        if(exit!=0)System.exit(exit);
    }
}
