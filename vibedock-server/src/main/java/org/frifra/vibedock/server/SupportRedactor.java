package org.frifra.vibedock.server;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secrets from every text of a support file: the known secrets literally, then by pattern bearer tokens,
 * 64-digit hex values (device keys, pins), web display links, JWT-like strings, password/token/secret/key values and
 * e-mail addresses; with hideHosts also host names and public IP addresses (private, loopback and link-local stay,
 * they help with network problems and identify nobody).
 */
final class SupportRedactor {
    private static final Pattern BEARER=Pattern.compile("(?i)(bearer\\s+)[^\\s\"',]+");
    private static final Pattern HEX64=Pattern.compile("(?i)\\b[0-9a-f]{64}\\b");
    private static final Pattern WEB=Pattern.compile("/web/[^\\s\"'/?#]+");
    private static final Pattern JWT=Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern ASSIGNMENT=Pattern.compile("(?i)\\b(password|passwd|token|access_token|refresh_token|secret|api[_-]?key|key|code)(\"?\\s*[=:]\\s*\"?)[^\\s\"',&]+");
    private static final Pattern EMAIL=Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern IPV4=Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");
    private static final Pattern HOST=Pattern.compile("(?i)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:[a-z]{2,24})\\b");
    /** Host names that say what failed without saying who the user is. */
    private static final Set<String> KNOWN_HOSTS=Set.of("api.anthropic.com","claude.ai","chatgpt.com","api.openai.com","auth.openai.com",
            "cursor.com","api.cursor.com","api.x.ai","x.ai","accounts.x.ai","generativelanguage.googleapis.com","oauth2.googleapis.com",
            "pool.ntp.org","time.cloudflare.com","crt.sh","api.open-meteo.com","geocoding-api.open-meteo.com","github.com");
    private final List<String> secrets;private final boolean hideHosts;
    SupportRedactor(Collection<String> secrets,boolean hideHosts){
        this.secrets=secrets.stream().filter(s->s!=null&&s.length()>=6).distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        this.hideHosts=hideHosts;
    }
    String text(String text){
        if(text==null)return null;
        for(String secret:secrets)text=text.replace(secret,"[secret]");
        text=BEARER.matcher(text).replaceAll("$1[redacted]");
        text=JWT.matcher(text).replaceAll("[redacted]");
        text=HEX64.matcher(text).replaceAll("[redacted]");
        text=WEB.matcher(text).replaceAll("/web/[redacted]");
        text=ASSIGNMENT.matcher(text).replaceAll("$1$2[redacted]");
        text=EMAIL.matcher(text).replaceAll("[e-mail]");
        if(hideHosts){
            text=IPV4.matcher(text).replaceAll(m->publicAddress(m)?"[address]":Matcher.quoteReplacement(m.group()));
            text=HOST.matcher(text).replaceAll(m->{
                String host=m.group().toLowerCase(Locale.ROOT);
                // Not file or class names (vibedock.log, org.frifra...): only names with a usual top-level domain.
                return KNOWN_HOSTS.contains(host)||!host.matches(".*\\.(com|net|org|de|io|ai|dev|app|eu|info|me|ch|at|nl|uk|fr|it|es|local|lan|home|arpa|cloud|synology\\.me)$")
                        ?Matcher.quoteReplacement(m.group()):"[host]";
            });
        }
        return text;
    }
    private static boolean publicAddress(java.util.regex.MatchResult m){
        int[] b=new int[4];
        for(int i=0;i<4;i++){b[i]=Integer.parseInt(m.group(i+1));if(b[i]>255)return false;}
        if(b[0]==10||b[0]==127||b[0]==0||(b[0]==169&&b[1]==254)||(b[0]==172&&b[1]>=16&&b[1]<=31)||(b[0]==192&&b[1]==168)||(b[0]==100&&b[1]>=64&&b[1]<=127))return false;
        return b[0]<224;
    }
}
