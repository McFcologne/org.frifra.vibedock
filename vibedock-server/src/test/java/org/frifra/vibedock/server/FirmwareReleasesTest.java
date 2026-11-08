package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FirmwareReleasesTest {
    /** A feed with releases made in memory: per source id, the releases and the bytes behind each address. */
    static class FakeFeed implements ReleaseFeed {
        final Map<String,List<Release>> releases=new HashMap<>();final Map<String,byte[]> files=new HashMap<>();int fetches;
        @Override public List<Release> releases(FirmwareSources.Source source) throws IOException {
            var list=releases.get(source.id());if(list==null)throw new IOException("GitHub answered 404 for "+source.repo());return list;
        }
        @Override public byte[] fetch(String url,int max) throws IOException {
            fetches++;var b=files.get(url);if(b==null)throw new IOException("not found "+url);
            if(b.length>max)throw new IOException("too large");return b;
        }
        /** A release of a version with the given boards; tamper: what to spoil. */
        void release(String source,String version,String protocol,String tamper,String... boards) throws Exception {
            var assets=new LinkedHashMap<String,String>();var entries=new ArrayList<String>();
            for(String id:boards){
                byte[] app=image(id,tamper.equals("identity")?"9.9.9":version),flash=Arrays.copyOf(("flash-"+id).getBytes(),2048);
                byte[] manifest=Arrays.copyOf(("{\"id\":\""+id+"\"}").getBytes(),2048);
                String base="vibedock-"+id+"-"+version;
                put(assets,source,version,base+".bin",flash);put(assets,source,version,base+".app.bin",app);put(assets,source,version,base+".manifest.json",manifest);
                String appHash=tamper.equals("hash")?"0".repeat(64):sha(app);
                entries.add("{\"id\":\""+id+"\",\"flash\":"+f(base+".bin",flash,sha(flash))+",\"app\":"+f(base+".app.bin",app,appHash)+",\"manifest\":"+f(base+".manifest.json",manifest,sha(manifest))+"}");
            }
            byte[] index=("{\"schema\":1,\"client\":\""+version+"\",\"protocol\":"+protocol+",\"boards\":["+String.join(",",entries)+"]}").getBytes();
            put(assets,source,version,"index.json",index);
            releases.computeIfAbsent(source,k->new ArrayList<>()).add(new Release("client-v"+version,version,assets));
            releases.get(source).sort((a,b)->FirmwareVersion.newer(a.version(),b.version())?-1:1);
        }
        private void put(Map<String,String> assets,String source,String version,String name,byte[] bytes){
            String url="https://example.invalid/"+source+"/"+version+"/"+name;assets.put(name,url);files.put(url,bytes);
        }
        private static String f(String name,byte[] b,String hash){return "{\"file\":\""+name+"\",\"sha256\":\""+hash+"\",\"size\":"+b.length+"}";}
    }
    static String sha(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
    static byte[] image(String board,String version){
        byte[] b=new byte[2048];b[0]=(byte)0xE9;byte[] id=("VIBEDOCK-FIRMWARE "+version+" "+board).getBytes(StandardCharsets.ISO_8859_1);System.arraycopy(id,0,b,200,id.length);return b;
    }
    private static FirmwareReleases service(Path data,FakeFeed feed){return new FirmwareReleases(data.toString(),true,feed);}
    private static void sources(Path data,String yaml) throws Exception {Files.writeString(data.resolve("firmware-sources.yaml"),yaml);}
    private static final Board CYD=Board.of("esp32-cyd"),M1=Board.M1;

    @Test void fetchesNewReleasesCheckedAndKeepsTheNewestTwo(@TempDir Path data) throws Exception {
        var feed=new FakeFeed();var releases=service(data,feed);
        for(String v:List.of("0.5.0","0.6.0","0.7.0"))feed.release("vibedock",v,"2","","esp32-cyd","vibedock-m1");
        releases.sync();
        var best=releases.best(CYD).orElseThrow();
        assertEquals("0.7.0",best.version());assertEquals("vibedock",best.source());assertTrue(best.trusted());
        assertArrayEquals(image("esp32-cyd","0.7.0"),Files.readAllBytes(best.dir().resolve("firmware.bin")));
        assertEquals("0.7.0\n",Files.readString(best.dir().resolve("version")));
        assertTrue(Files.isRegularFile(best.dir().resolve("manifest.json")));
        assertFalse(Files.exists(data.resolve("firmware/releases/vibedock/0.5.0")),"only the newest two stay");
        assertTrue(Files.isDirectory(data.resolve("firmware/releases/vibedock/0.6.0")));
        int after=feed.fetches;releases.sync();
        assertEquals(after,feed.fetches,"what is on disk is not fetched again");
        assertNotNull(releases.status().get(0).checkedAt());assertNull(releases.status().get(0).error());
        assertEquals("0.7.0",releases.status().get(0).latest());
    }
    @Test void aReleaseWithAWrongHashIdentityOrProtocolIsNotTaken(@TempDir Path data) throws Exception {
        var feed=new FakeFeed();var releases=service(data,feed);
        feed.release("vibedock","0.6.0","2","hash","esp32-cyd");
        releases.sync();
        assertTrue(releases.best(CYD).isEmpty());assertTrue(releases.status().get(0).error().contains("SHA-256"));
        feed.releases.clear();feed.release("vibedock","0.6.1","2","identity","esp32-cyd");
        releases.sync();assertTrue(releases.best(CYD).isEmpty());assertTrue(releases.status().get(0).error().contains("not esp32-cyd 0.6.1"));
        feed.releases.clear();feed.release("vibedock","0.6.2","3","","esp32-cyd");
        releases.sync();assertTrue(releases.best(CYD).isEmpty());assertTrue(releases.status().get(0).error().contains("protocol 3"));
        assertFalse(Files.exists(data.resolve("firmware/releases/vibedock/0.6.2/esp32-cyd")));
        try(var s=Files.list(data.resolve("firmware/releases/vibedock"))){assertTrue(s.noneMatch(p->p.getFileName().toString().startsWith(".tmp")),"no leftovers");}
    }
    @Test void aGoodOlderReleaseServesWhileTheNewestIsBad(@TempDir Path data) throws Exception {
        var feed=new FakeFeed();var releases=service(data,feed);
        feed.release("vibedock","0.6.0","2","","esp32-cyd");feed.release("vibedock","0.7.0","2","hash","esp32-cyd");
        releases.sync();
        assertEquals("0.6.0",releases.best(CYD).orElseThrow().version());
    }
    @Test void theFirstSourceWithFirmwareForTheBoardWinsAndAnUnknownSourceIsNotTrusted(@TempDir Path data) throws Exception {
        sources(data,"""
                sources:
                  - id: alt
                    repo: someone/alt
                    tagPrefix: v
                    boards: [esp32-cyd]
                  - id: vibedock
                    repo: McFcologne/org.frifra.vibedock
                    tagPrefix: client-v
                    trusted: true
                """);
        var feed=new FakeFeed();var releases=service(data,feed);
        feed.release("alt","1.0.0","2","","esp32-cyd","vibedock-m1");feed.release("vibedock","0.6.0","2","","esp32-cyd","vibedock-m1");
        releases.sync();
        var cyd=releases.best(CYD).orElseThrow();assertEquals("alt",cyd.source());assertFalse(cyd.trusted());
        assertEquals("vibedock",releases.best(M1).orElseThrow().source(),"the alt source offers only the CYD");
    }
    @Test void aDisabledSourceAndAMistakeInTheFileFetchNothing(@TempDir Path data) throws Exception {
        sources(data,"sources:\n  - id: a\n    repo: o/r\n    tagPrefix: v\n    enabled: false\n");
        var feed=new FakeFeed();feed.release("a","1.0.0","2","","esp32-cyd");var releases=service(data,feed);
        releases.sync();assertEquals(0,feed.fetches);assertTrue(releases.best(CYD).isEmpty());
        sources(data,"sources: nonsense");
        assertNotNull(releases.configError());assertTrue(releases.sources().isEmpty());releases.sync();assertEquals(0,feed.fetches);
    }
    @Test void aSourceThatFailsDoesNotStopTheOthers(@TempDir Path data) throws Exception {
        sources(data,"sources:\n  - id: gone\n    repo: o/gone\n    tagPrefix: v\n  - id: vibedock\n    repo: McFcologne/org.frifra.vibedock\n    tagPrefix: client-v\n");
        var feed=new FakeFeed();feed.release("vibedock","0.6.0","2","","esp32-cyd");var releases=service(data,feed);
        releases.sync();
        assertEquals("0.6.0",releases.best(CYD).orElseThrow().version());
        assertTrue(releases.status().get(0).error().contains("404"));assertNull(releases.status().get(1).error());
    }

    @Test void downloadedFirmwareServesInsteadOfTheBuiltInOneOnlyWhenNewer(@TempDir Path root) throws Exception {
        Path app=Files.createDirectories(root.resolve("app")),data=Files.createDirectories(root.resolve("data"));
        var cyd=Files.createDirectories(app.resolve("esp32-cyd"));
        Files.write(cyd.resolve("flash.bin"),"builtin-flash".getBytes());Files.write(cyd.resolve("firmware.bin"),"builtin-app".getBytes());
        Files.writeString(app.resolve("version"),"0.6.0\n");
        var feed=new FakeFeed();var releases=service(data,feed);
        var firmware=new Firmware(app.toString(),data.toString());firmware.releases(releases);
        feed.release("vibedock","0.6.0","2","","esp32-cyd");releases.sync();
        assertEquals("0.6.0",firmware.info(CYD).orElseThrow().version());assertNull(firmware.info(CYD).orElseThrow().source(),"the same version: the built-in one");
        feed.release("vibedock","0.7.0","2","","esp32-cyd");releases.sync();
        var info=firmware.info(CYD).orElseThrow();
        assertEquals("0.7.0",info.version());assertEquals("vibedock",info.source());assertTrue(info.wifi());assertFalse(info.uploaded());
        assertEquals("0.7.0",firmware.versions().get("esp32-cyd"));
        assertArrayEquals(Arrays.copyOf(("flash-esp32-cyd").getBytes(),2048),firmware.bytes(CYD));
    }
    @Test void firmwareOfASourceThatIsNotTrustedIsForUsbOnly(@TempDir Path root) throws Exception {
        Path app=Files.createDirectories(root.resolve("app")),data=Files.createDirectories(root.resolve("data"));
        sources(data,"sources:\n  - id: alt\n    repo: someone/alt\n    tagPrefix: v\n");
        var feed=new FakeFeed();var releases=service(data,feed);
        var firmware=new Firmware(app.toString(),data.toString());firmware.releases(releases);
        feed.release("alt","1.0.0","2","","esp32-cyd");releases.sync();
        var info=firmware.info(CYD).orElseThrow();
        assertEquals("alt",info.source());assertFalse(info.wifi());
        assertTrue(firmware.versions().isEmpty(),"no update is offered over Wi-Fi");
        assertTrue(firmware.signed(CYD,null).isEmpty());
        assertTrue(firmware.bytes(CYD).length>0,"still for flashing over USB");
    }
}
