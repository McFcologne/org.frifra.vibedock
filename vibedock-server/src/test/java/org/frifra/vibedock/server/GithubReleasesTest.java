package org.frifra.vibedock.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** The GitHub API as a small local server: the list of releases, its ETag, the filters, the files and the address check. */
class GithubReleasesTest {
    private HttpServer server;private final AtomicInteger lists=new AtomicInteger(),conditional=new AtomicInteger();
    private String api;
    private static final String LIST="""
            [{"tag_name":"client-v0.7.0","draft":false,"prerelease":false,"assets":[{"name":"index.json","browser_download_url":"%s/dl/index-0.7.0"}]},
             {"tag_name":"client-v0.8.0","draft":true,"prerelease":false,"assets":[]},
             {"tag_name":"client-v0.9.0-rc1","draft":false,"prerelease":true,"assets":[]},
             {"tag_name":"v0.5.1","draft":false,"prerelease":false,"assets":[]},
             {"tag_name":"client-v0.6.0","draft":false,"prerelease":false,"assets":[{"name":"index.json","browser_download_url":"%s/dl/index-0.6.0"}]},
             {"tag_name":"client-vlatest","draft":false,"prerelease":false,"assets":[]}]""";

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        api="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/repos/o/r/releases",x->{
            lists.incrementAndGet();
            if("\"v1\"".equals(x.getRequestHeaders().getFirst("If-None-Match"))){conditional.incrementAndGet();x.sendResponseHeaders(304,-1);x.close();return;}
            byte[] b=LIST.formatted(api,api).getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("ETag","\"v1\"");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();
        });
        server.createContext("/repos/o/missing/releases",x->{x.sendResponseHeaders(404,-1);x.close();});
        server.createContext("/dl/",x->{
            byte[] b=new byte[1000];x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();
        });
        server.createContext("/big",x->{byte[] b=new byte[5000];x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});
        server.start();
    }
    @AfterEach void stop(){server.stop(0);}

    private static FirmwareSources.Source source(String repo,String prefix){return new FirmwareSources.Source("s","s",repo,prefix,false,true,java.util.Set.of());}

    @Test void listsPublishedReleasesOfTheTagPrefixNewestFirstAndAsksWithTheETag() throws Exception {
        var feed=new GithubReleases(api);
        var list=feed.releases(source("o/r","client-v"));
        assertEquals(java.util.List.of("0.7.0","0.6.0"),list.stream().map(ReleaseFeed.Release::version).toList(),"no draft, no pre-release, no other prefix, no non-version");
        assertEquals(api+"/dl/index-0.7.0",list.get(0).assets().get("index.json"));
        assertEquals(list,feed.releases(source("o/r","client-v")));
        assertEquals(2,lists.get());assertEquals(1,conditional.get(),"the second time with the ETag, answered 304");
        assertEquals(java.util.List.of("0.5.1"),feed.releases(source("o/r","v")).stream().map(ReleaseFeed.Release::version).toList());
    }
    @Test void anErrorOfTheServerIsAnError(){
        var e=assertThrows(java.io.IOException.class,()->new GithubReleases(api).releases(source("o/missing","v")));
        assertTrue(e.getMessage().contains("404"));
    }
    @Test void fetchesFilesUpToTheGivenSize() throws Exception {
        var feed=new GithubReleases(api);
        assertEquals(1000,feed.fetch(api+"/dl/x",2000).length);
        assertTrue(assertThrows(java.io.IOException.class,()->feed.fetch(api+"/big",2000)).getMessage().contains("larger"));
    }
    @Test void onlyGithubHostsAreAskedUnlessTheApiAddressWasSet(){
        var github=new GithubReleases("https://api.github.com");
        assertTrue(assertThrows(java.io.IOException.class,()->github.fetch("https://example.com/x",10)).getMessage().contains("not allowed"));
        assertTrue(assertThrows(java.io.IOException.class,()->github.fetch("http://github.com/x",10)).getMessage().contains("not allowed"));
        assertTrue(assertThrows(java.io.IOException.class,()->github.fetch("https://github.com.evil.example/x",10)).getMessage().contains("not allowed"));
        assertTrue(assertThrows(java.io.IOException.class,()->github.fetch("http://127.0.0.1:1/x",10)).getMessage().contains("not allowed"));
    }
}
