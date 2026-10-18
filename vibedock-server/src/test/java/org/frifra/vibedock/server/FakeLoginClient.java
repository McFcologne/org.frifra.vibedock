package org.frifra.vibedock.server;

import java.io.*;
import java.nio.file.*;

/** Local login-process simulator. Never contacts a provider. */
public class FakeLoginClient {
    public static void main(String[] args) throws Exception {
        boolean codex=args.length>0 && args[0].equals("-c");
        var home=Path.of(System.getenv(codex?"CODEX_HOME":"CLAUDE_CONFIG_DIR"));
        if (codex) {
            var in=new BufferedReader(new InputStreamReader(System.in));
            if (!in.readLine().contains("initialize")) System.exit(2);
            System.out.println("{\"id\":0,\"result\":{}}");System.out.flush();
            if (!in.readLine().contains("initialized")) System.exit(3);
            if (!in.readLine().contains("chatgptDeviceCode")) System.exit(4);
            System.out.println("{\"id\":1,\"result\":{\"verificationUrl\":\"https://auth.openai.com/codex/device\",\"userCode\":\"TEST-1234\"}}");System.out.flush();
            while (!Files.exists(Path.of(System.getenv("REVIEW_APPROVAL")))) Thread.sleep(20);
            Files.writeString(home.resolve("auth.json"),"{\"tokens\":{\"access_token\":\"synthetic-codex-token\",\"account_id\":\"synthetic-workspace\"}}");
            System.out.println("{\"method\":\"account/login/completed\",\"params\":{\"success\":true}}");System.out.flush();
            Thread.sleep(30000);
        } else {
            if (args.length!=4 || !args[0].equals("auth") || !args[1].equals("login")
                    || !args[2].equals("--claudeai") || !args[3].equals("--sso")) System.exit(5);
            System.out.print("https://claude.ai/oauth/authorize?client_id=fixture&");System.out.flush();Thread.sleep(40);
            System.out.println("state=synthetic-state");System.out.print("Paste code here if prompted: ");System.out.flush();
            String code=new BufferedReader(new InputStreamReader(System.in)).readLine();
            if (!"test-code#test-state".equals(code)) System.exit(6);
            Files.writeString(home.resolve(".credentials.json"),"{\"claudeAiOauth\":{\"accessToken\":\"synthetic-claude-token\"}}");
        }
    }
}
