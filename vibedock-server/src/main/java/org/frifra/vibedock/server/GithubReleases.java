package org.frifra.vibedock.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The releases of a GitHub repository through its API, asked with the ETag of the last answer (an unchanged list, 304,
 * does not count against the rate limit). Requests go only to GitHub's hosts over https; the address of the API can be
 * set for a mirror or a test ({@code vibedock.firmware-api}), which then is allowed as well (also over http).
 */
@Component
class GithubReleases implements ReleaseFeed {
    private static final Pattern VERSION=Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.]+)?");
    private static final tools.jackson.databind.ObjectMapper JSON=JsonCompatibility.mapper();
    private final URI api;private final boolean custom;
    private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();
    private record Answer(String etag,List<Release> releases) {}
    private final Map<String,Answer> answers=new HashMap<>();

    GithubReleases(@Value("${vibedock.firmware-api:https://api.github.com}") String api){
        this.api=URI.create(api.endsWith("/")?api.substring(0,api.length()-1):api);
        this.custom=!this.api.getHost().equals("api.github.com");
    }
    private boolean allowed(URI uri){
        String host=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
        if(custom&&host.equals(api.getHost())&&(uri.getPort()==api.getPort()))return true;
        return "https".equals(uri.getScheme())&&uri.getUserInfo()==null
                &&(host.equals("api.github.com")||host.equals("github.com")||host.endsWith(".githubusercontent.com")||host.equals("githubusercontent.com"));
    }
    private HttpRequest.Builder request(URI uri){
        if(!allowed(uri))throw new IllegalArgumentException("address not allowed: "+uri.getHost());
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent","vibedock-server").header("Accept","application/vnd.github+json");
    }
    @Override public synchronized List<Release> releases(FirmwareSources.Source source) throws IOException {
        var uri=URI.create(api+"/repos/"+source.repo()+"/releases?per_page=30");
        var before=answers.get(source.repo());
        var builder=request(uri);if(before!=null)builder.header("If-None-Match",before.etag());
        HttpResponse<byte[]> response;
        try{response=http.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("interrupted");}
        if(response.statusCode()==304&&before!=null)return filter(before.releases(),source);
        if(response.statusCode()!=200)throw new IOException("GitHub answered "+response.statusCode()+" for "+source.repo());
        if(response.body().length>2*1024*1024)throw new IOException("the list of releases is too large");
        JsonNode root=JSON.readTree(response.body());
        if(!root.isArray())throw new IOException("unexpected answer for "+source.repo());
        var all=new ArrayList<Release>();
        for(var r:root){
            if(r.path("draft").asBoolean(false)||r.path("prerelease").asBoolean(false)||!r.path("tag_name").isString())continue;
            var assets=new LinkedHashMap<String,String>();
            for(var a:r.path("assets"))if(a.path("name").isString()&&a.path("browser_download_url").isString())assets.put(a.path("name").asString(),a.path("browser_download_url").asString());
            all.add(new Release(r.path("tag_name").asString(),null,assets));
        }
        response.headers().firstValue("ETag").ifPresent(e->answers.put(source.repo(),new Answer(e,all)));
        return filter(all,source);
    }
    private static List<Release> filter(List<Release> all,FirmwareSources.Source source){
        var out=new ArrayList<Release>();
        for(var r:all){
            if(!r.tag().startsWith(source.tagPrefix()))continue;
            String version=r.tag().substring(source.tagPrefix().length());
            if(VERSION.matcher(version).matches())out.add(new Release(r.tag(),version,r.assets()));
        }
        out.sort((a,b)->FirmwareVersion.newer(a.version(),b.version())?-1:FirmwareVersion.newer(b.version(),a.version())?1:0);
        return out;
    }
    @Override public byte[] fetch(String url,int maxBytes) throws IOException {
        try{
            var response=http.send(request(URI.create(url)).header("Accept","application/octet-stream").build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var in=response.body()){
                if(response.statusCode()!=200)throw new IOException("answer "+response.statusCode()+" for a release file");
                if(!allowed(response.uri()))throw new IOException("redirected to "+response.uri().getHost());
                byte[] bytes=in.readNBytes(maxBytes+1);
                if(bytes.length>maxBytes)throw new IOException("a release file is larger than "+maxBytes+" bytes");
                return bytes;
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("interrupted");}
        catch(IllegalArgumentException e){throw new IOException(e.getMessage());}
    }
}
