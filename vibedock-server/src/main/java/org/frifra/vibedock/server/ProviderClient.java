package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@Component
public class ProviderClient {
    /** usedPercent 0-100; resetsAt epoch seconds (0: not started yet); seconds: window length if known, else 0. */
    public record Window(double usedPercent,long resetsAt,long seconds) {
        public Window(double usedPercent,long resetsAt){this(usedPercent,resetsAt,0);}
    }
    public record Reading(Window fiveHour,Window week) {}
    static class Failure extends Exception {
        final String status;
        final long retryAt;
        final String detail;
        Failure(String status) { this(status,0,""); }
        Failure(String status,long retryAt) { this(status,retryAt,""); }
        Failure(String status,long retryAt,String detail) { super(status+(detail.isEmpty()?"":" ("+detail+")")); this.status=status; this.retryAt=retryAt; this.detail=detail; }
    }
    private final HttpClient http;
    private final ObjectMapper json;
    public ProviderClient(HttpClient http,ObjectMapper json) { this.http=http; this.json=json; }
    public Reading fetch(String provider,SettingsStore.Credential credential) throws Exception {
        if (provider.equals("cursor")) return cursor(credential);
        if (provider.equals("grok")) return grok(credential);
        if (provider.equals("gemini")) return gemini(credential);
        URI uri=URI.create(provider.equals("codex") ? "https://chatgpt.com/backend-api/wham/usage"
                : "https://api.anthropic.com/api/oauth/usage");
        HttpRequest.Builder request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer " + credential.token())
                .header("Accept","application/json").header("User-Agent","VibeDock-Server/0.1");
        if (provider.equals("claude")) request.header("anthropic-beta","oauth-2025-04-20");
        if (provider.equals("codex") && !credential.accountId().isBlank())
            request.header("ChatGPT-Account-Id",credential.accountId());
        var response=http.send(request.GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        try (var body=response.body()) {
            if (response.statusCode()==401 || response.statusCode()==403) throw new Failure("authentication_required",0,"HTTP "+response.statusCode());
            if (response.statusCode()==429) throw new Failure("rate_limited",retryAt(response.headers().firstValue("Retry-After").orElse("60")),"HTTP 429");
            if (response.statusCode()!=200) throw new Failure("upstream_error",0,"HTTP "+response.statusCode());
            byte[] bytes=body.readNBytes(65537);
            if (bytes.length>65536) throw new Failure("invalid_response");
            return parse(provider,json.readTree(bytes));
        }
    }
    /**
     * Cursor: the team's Admin API (Basic auth with the admin API key) and the member's spend in the current billing
     * cycle. accountId holds the member's e-mail. The share used comes from totalPercentUsed, or from the spend and
     * the member's limit; it is reported as the single monthly window.
     */
    private Reading cursor(SettingsStore.Credential credential) throws Exception {
        String email=credential.accountId().trim();
        if (email.isEmpty()) throw new Failure("unavailable",0,"member e-mail missing");
        var body=json.writeValueAsBytes(Map.of("searchTerm",email,"page",1,"pageSize",50));
        var request=HttpRequest.newBuilder(URI.create("https://api.cursor.com/teams/spend")).timeout(Duration.ofSeconds(10))
                .header("Authorization","Basic "+Base64.getEncoder().encodeToString((credential.token()+":").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Content-Type","application/json").header("Accept","application/json").header("User-Agent","VibeDock-Server/0.1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (var in=response.body()) {
            if (response.statusCode()==401 || response.statusCode()==403) throw new Failure("authentication_required",0,"HTTP "+response.statusCode());
            if (response.statusCode()==429) throw new Failure("rate_limited",retryAt(response.headers().firstValue("Retry-After").orElse("60")),"HTTP 429");
            if (response.statusCode()!=200) throw new Failure("upstream_error",0,"HTTP "+response.statusCode());
            byte[] bytes=in.readNBytes(262145);
            if (bytes.length>262144) throw new Failure("invalid_response");
            return parseCursor(json.readTree(bytes),email);
        }
    }
    static Reading parseCursor(JsonNode root,String email) throws IOException,Failure {
        JsonNode member=null;
        for (JsonNode m:root.path("teamMemberSpend")) if (email.equalsIgnoreCase(m.path("email").asText())) member=m;
        if (member==null) throw new Failure("unavailable",0,"no team member "+email);
        if (!root.path("subscriptionCycleStart").canConvertToLong()) throw new IOException("billing cycle missing");
        long reset=ZonedDateTime.ofInstant(Instant.ofEpochMilli(root.path("subscriptionCycleStart").longValue()),ZoneOffset.UTC).plusMonths(1).toEpochSecond();
        double used;
        if (member.path("totalPercentUsed").isNumber()) used=member.path("totalPercentUsed").doubleValue();
        else {
            double limit=member.path("hardLimitOverrideDollars").asDouble(0)>0?member.path("hardLimitOverrideDollars").asDouble():member.path("monthlyLimitDollars").asDouble(0);
            double cents=member.has("overallSpendCents")?member.path("overallSpendCents").asDouble(0)
                    :member.path("includedSpendCents").asDouble(0)+member.path("spendCents").asDouble(0);
            if (limit<=0) throw new Failure("unavailable",0,String.format(Locale.ROOT,"no limit for %s, spend $%.2f",email,cents/100));
            used=cents/limit;
        }
        if (!Double.isFinite(used) || used<0) throw new IOException("percent out of bounds");
        return new Reading(null,new Window(Math.min(100,used),reset,30L*86400));
    }
    /**
     * Grok: the credit usage of the subscription from the Grok Build billing endpoint (not officially documented),
     * with the Grok Build sign-in. The weekly (or monthly) pool is reported as the single long window.
     */
    private Reading grok(SettingsStore.Credential credential) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("https://cli-chat-proxy.grok.com/v1/billing?format=credits")).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+credential.token()).header("X-XAI-Token-Auth","xai-grok-cli")
                .header("Accept","application/json").header("User-Agent","VibeDock-Server/0.1").GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (var in=response.body()) {
            if (response.statusCode()==401 || response.statusCode()==403) throw new Failure("authentication_required",0,"HTTP "+response.statusCode());
            if (response.statusCode()==429) throw new Failure("rate_limited",retryAt(response.headers().firstValue("Retry-After").orElse("60")),"HTTP 429");
            if (response.statusCode()!=200) throw new Failure("upstream_error",0,"HTTP "+response.statusCode());
            byte[] bytes=in.readNBytes(262145);
            if (bytes.length>262144) throw new Failure("invalid_response");
            return parseGrok(json.readTree(bytes));
        }
    }
    static Reading parseGrok(JsonNode root) throws IOException,Failure {
        JsonNode config=root.path("config");
        if (config.isMissingNode() && root.path("creditUsagePercent").isMissingNode()) throw new Failure("unavailable",0,"no credit usage in the answer");
        // A pool without usage yet leaves the percent out.
        JsonNode percent=config.has("creditUsagePercent")?config.path("creditUsagePercent"):root.path("creditUsagePercent");
        double used=percent.isNumber()?percent.doubleValue():0;
        if (!Double.isFinite(used) || used<0) throw new IOException("percent out of bounds");
        JsonNode period=root.path("currentPeriod");
        long start=SignInRenewal.epochSeconds(period.has("start")?period.path("start"):root.path("billingPeriodStart"));
        long end=SignInRenewal.epochSeconds(period.has("end")?period.path("end"):root.path("billingPeriodEnd"));
        long seconds=start>0&&end>start?end-start:period.toString().toUpperCase(Locale.ROOT).contains("MONTH")?30L*86400:7L*86400;
        return new Reading(null,new Window(Math.min(100,used),Math.max(0,end),seconds));
    }
    static final String GEMINI_ENDPOINT="https://cloudcode-pa.googleapis.com/v1internal:";
    /**
     * Gemini: the daily request quota of the Gemini CLI sign-in (Gemini Code Assist), read like the CLI itself does:
     * loadCodeAssist names the Code Assist project of the account, retrieveUserQuota returns one bucket per model
     * with the remaining fraction and the reset time. Neither call spends quota. The model with the least left is
     * reported as the single daily window. Both endpoints are internal and not documented.
     */
    private Reading gemini(SettingsStore.Credential credential) throws Exception {
        var metadata=Map.of("ideType","IDE_UNSPECIFIED","platform","PLATFORM_UNSPECIFIED","pluginType","GEMINI");
        JsonNode load=geminiPost(credential,"loadCodeAssist",Map.of("metadata",metadata));
        JsonNode project=load.path("cloudaicompanionProject");
        String id=project.isTextual()?project.textValue():project.path("id").asText("");
        if (id.isBlank()) throw new Failure("unavailable",0,"no Code Assist project; open the Gemini CLI once with this account");
        return parseGemini(geminiPost(credential,"retrieveUserQuota",Map.of("project",id)),Instant.now().getEpochSecond());
    }
    private JsonNode geminiPost(SettingsStore.Credential credential,String method,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(GEMINI_ENDPOINT+method)).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+credential.token()).header("Content-Type","application/json")
                .header("Accept","application/json").header("User-Agent","VibeDock-Server/0.1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try (var in=response.body()) {
            if (response.statusCode()==401 || response.statusCode()==403) throw new Failure("authentication_required",0,"HTTP "+response.statusCode()+" "+method);
            if (response.statusCode()==429) throw new Failure("rate_limited",retryAt(response.headers().firstValue("Retry-After").orElse("60")),"HTTP 429");
            if (response.statusCode()!=200) throw new Failure("upstream_error",0,"HTTP "+response.statusCode()+" "+method);
            byte[] bytes=in.readNBytes(262145);
            if (bytes.length>262144) throw new Failure("invalid_response");
            return json.readTree(bytes);
        }
    }
    static Reading parseGemini(JsonNode root,long now) throws IOException,Failure {
        Window lowest=null;
        for (JsonNode bucket:root.path("buckets")) {
            JsonNode fraction=bucket.path("remainingFraction");
            if (!fraction.isNumber()) continue;
            double left=fraction.doubleValue();
            if (!Double.isFinite(left) || left<0 || left>1) throw new IOException("fraction out of bounds");
            long reset=SignInRenewal.epochSeconds(bucket.path("resetTime"));
            var window=new Window(100*(1-left),reset>now?reset:0,86400);
            if (lowest==null || window.usedPercent()>lowest.usedPercent()) lowest=window;
        }
        if (lowest==null) throw new Failure("unavailable",0,"no quota buckets in the answer");
        return new Reading(null,lowest);
    }
    static long retryAt(String value) {
        long now=Instant.now().getEpochSecond();
        try { return now+Math.max(60,Math.min(86400,Long.parseLong(value))); }
        catch (NumberFormatException ignored) {
            try { return Math.max(now+60,ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()); }
            catch (DateTimeException e) { return now+60; }
        }
    }
    static Reading parse(String provider,JsonNode root) throws IOException,Failure {
        Window five=null,week=null;
        if (provider.equals("claude")) {
            five=claudeWindow(root.path("five_hour")); week=claudeWindow(root.path("seven_day"));
        } else {
            // Plans differ in their windows; up to a day counts as the short window, longer ones as the week.
            List<Long> seen=new ArrayList<>();
            for (String name:new String[]{"primary_window","secondary_window"}) {
                JsonNode n=root.path("rate_limit").path(name);
                if (n.isMissingNode() || n.isNull()) continue;
                if (!n.path("limit_window_seconds").isIntegralNumber() || !n.path("limit_window_seconds").canConvertToLong()) throw new IOException("window duration missing");
                long duration=n.path("limit_window_seconds").longValue();seen.add(duration);
                Window raw=window(n.path("used_percent"),n.path("reset_at"));Window w=new Window(raw.usedPercent(),raw.resetsAt(),duration);
                if (duration<=86400) { if (five==null) five=w; } else if (week==null) week=w;
            }
            if (five==null && week==null)
                throw new Failure("unavailable",0,root.path("rate_limit").isMissingNode()||root.path("rate_limit").isNull()
                        ? "no rate_limit in the answer, plan "+root.path("plan_type").asText("?") : "windows "+seen);
        }
        return new Reading(five,week);
    }
    private static Window claudeWindow(JsonNode n) throws IOException {
        if (n.isMissingNode() || n.isNull()) return null;
        // A window without usage yet has no reset time.
        if (n.path("resets_at").isNull() || n.path("resets_at").isMissingNode()) {
            if (!n.path("utilization").isNumber()) throw new IOException("utilization missing");
            return new Window(Math.max(0,Math.min(100,n.path("utilization").doubleValue())),0);
        }
        if (!n.path("resets_at").isTextual()) throw new IOException("reset missing");
        try { return window(n.path("utilization"),new ObjectMapper().getNodeFactory()
                .numberNode(OffsetDateTime.parse(n.path("resets_at").textValue()).toEpochSecond())); }
        catch (DateTimeException e) { throw new IOException("reset malformed"); }
    }
    private static Window window(JsonNode percent,JsonNode reset) throws IOException {
        if (!percent.isNumber() || !reset.isIntegralNumber() || !reset.canConvertToLong()) throw new IOException("window malformed");
        double value=percent.doubleValue();long time=reset.longValue();
        if (!Double.isFinite(value) || value<0 || value>100 || time<=0) throw new IOException("window out of bounds");
        return new Window(value,time);
    }
}
