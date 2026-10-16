package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LayoutTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final String ID="vibedock-design-test";
    private static final Layout CUSTOM=layout(List.of(2,1,0),true);
    /** Bands in the given order: 0 = usage codex+claude, 1 = usage claude+codex, 2 = context. */
    static Layout layout(List<Integer> order,boolean moodLeft){
        List<Layout.Band> all=List.of(new Layout.Usage(List.of("codex","claude")),new Layout.Usage(List.of("claude","codex")),new Layout.Context(moodLeft));
        return new Layout(order.stream().map(all::get).toList());
    }
    private static BufferedImage icon(int size){
        var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
        try{g.setColor(Color.YELLOW);g.fillOval(0,0,size,size);g.setColor(Color.WHITE);g.fillRect(size/3,size/3,size/2,size/3);}finally{g.dispose();}return image;
    }
    static UsageService.Snapshot snapshot(int variant){
        var window=new ProviderClient.Window(variant==0?1:83,1900000000L);
        return new UsageService.Snapshot(1,1800000000,Map.of(
                "codex",new UsageService.Provider(variant==2?"authentication_required":"ok",variant==2?null:1800000000L,variant==2?null:window,window),
                "claude",new UsageService.Provider(variant==1?"rate_limited":"ok",1800000000L,window,variant==2?null:window)));
    }
    @Test void defaultIsPixelIdenticalToTheFrozenRendererInEveryTheme() throws Exception {
        var before=new LegacyStatusImage("Europe/Berlin");var after=new StatusImage("Europe/Berlin");
        for(var scheme:Scheme.values())for(int variant=0;variant<3;variant++)for(boolean weather:List.of(false,true)){
            var oldLook=new LegacyStatusImage.Look(icon(44),scheme,weather?icon(24):null,weather?"13°":null);
            var look=new StatusImage.Look(icon(44),scheme,weather?icon(24):null,weather?"13°":null,Layout.DEFAULT);
            var expected=before.render(snapshot(variant),"192.168.1.50",oldLook);var actual=after.render(snapshot(variant),"192.168.1.50",look);
            assertArrayEquals(expected.rgb565(),actual.rgb565(),scheme+" variant "+variant+" weather "+weather);
            assertArrayEquals(expected.png(),actual.png(),scheme+" PNG");
        }
    }
    @Test void customLayoutsKeepTheFooterAndWirePixelsForEveryTheme() throws Exception {
        var renderer=new StatusImage("Europe/Berlin");
        var orders=List.of(List.of(0,1,2),List.of(1,0,2),List.of(2,0,1),List.of(0,2,1),List.of(1,2,0),List.of(2,1,0));
        for(var scheme:Scheme.values())for(var order:orders)for(boolean moodLeft:List.of(false,true)){
            var layout=layout(order,moodLeft);
            var actual=renderer.render(snapshot(0),"192.168.1.50",new StatusImage.Look(icon(44),scheme,icon(24),"13°",layout));
            var original=renderer.render(snapshot(0),"192.168.1.50",new StatusImage.Look(icon(44),scheme,icon(24),"13°"));
            assertEquals(115200,actual.rgb565().length);
            if(order.equals(List.of(0,1,2))&&!moodLeft){
                Files.createDirectories(Path.of("target/layout-previews"));
                Files.write(Path.of("target/layout-previews/"+scheme.id()+".png"),actual.png());
            }
            // The IP corner moves with the weather/mood band and looks the same wherever it is.
            int context=order.indexOf(2)*Layout.USAGE_HEIGHT+36;
            for(int y=0;y<20;y++)assertArrayEquals(Arrays.copyOfRange(original.rgb565(),(220+y)*480,(220+y)*480+300),
                    Arrays.copyOfRange(actual.rgb565(),(context+y)*480,(context+y)*480+300),scheme+" IP corner "+order);
            var png=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(actual.png()));
            for(int y=0;y<240;y++)for(int x=0;x<240;x++){
                int offset=(y*240+x)*2,value=(actual.rgb565()[offset]&255)<<8|(actual.rgb565()[offset+1]&255);
                int expected=((value>>11&31)*255/31<<16)|((value>>5&63)*255/63<<8)|(value&31)*255/31;
                assertEquals(expected,png.getRGB(x,y)&0xffffff);
            }
        }
    }
    @Test void usageBandsKeepTheirHeightWhenTheWeatherBandMovesUp() throws Exception {
        var renderer=new StatusImage("Europe/Berlin");
        var moved=new Layout(List.of(new Layout.Context(false),new Layout.Usage(List.of("codex")),new Layout.Usage(List.of("claude"))));
        for(var scheme:Scheme.values())for(int variant=0;variant<3;variant++){
            var original=renderer.render(snapshot(variant),"192.168.1.50",new StatusImage.Look(icon(44),scheme,icon(24),"13°")).rgb565();
            var actual=renderer.render(snapshot(variant),"192.168.1.50",new StatusImage.Look(icon(44),scheme,icon(24),"13°",moved)).rgb565();
            // Windows of the band at the screen edge get a margin below, so with Mac and Windows only the upper band is identical.
            int rows=scheme.style==Scheme.Style.MAC||scheme.style==Scheme.Style.WINDOWS?92:184;
            assertArrayEquals(Arrays.copyOfRange(original,0,rows*480),Arrays.copyOfRange(actual,56*480,(56+rows)*480),scheme+" usage bands, variant "+variant);
            // From row 2: the shadow of a classic Mac window above reaches two rows into the weather band.
            assertArrayEquals(Arrays.copyOfRange(original,186*480,240*480),Arrays.copyOfRange(actual,2*480,56*480),scheme+" weather band, variant "+variant);
        }
    }
    @Test void olderFirmwareKeepsTheWeatherBandAtTheBottom(@TempDir Path dir) throws Exception {
        var moved=new Design(Scheme.OCEAN,"",null,CUSTOM);
        assertEquals(moved,DesignService.forDevice(moved,true));
        assertEquals(CUSTOM.withContextAtBottom(),DesignService.forDevice(moved,false).layout());
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());registry.paired(ID,"Desk","a".repeat(64),"192.168.1.20",80);
        assertFalse(registry.get(ID).serverFooter());
        registry.pulled(ID,"192.168.1.20",true);assertTrue(new ClientRegistry(dir.toString(),json,Clock.systemUTC()).get(ID).serverFooter());
        registry.pulled(ID,"192.168.1.20",false);assertFalse(new ClientRegistry(dir.toString(),json,Clock.systemUTC()).get(ID).serverFooter());
    }
    @Test void completeOverridesPersistAndResetToFutureDefaults(@TempDir Path dir) throws Exception {
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());registry.paired(ID,"Desk","a".repeat(64),"192.168.1.20",80);
        var custom=new Design(Scheme.CLASSIC_WINDOWS,"",null,CUSTOM);registry.configureDesign(custom);
        assertEquals(custom,registry.designFor(ID));assertFalse(registry.get(ID).overridden());
        var own=new Design(Scheme.SAND,"",null,Layout.DEFAULT);registry.setDesign(ID,own);
        registry.configureDesign(new Design(Scheme.FOREST,"",null,CUSTOM));
        var loaded=new ClientRegistry(dir.toString(),json,Clock.systemUTC());assertEquals(own,loaded.designFor(ID));assertTrue(loaded.get(ID).overridden());
        loaded.pulled(ID,"192.168.1.21");loaded.paired(ID,"Desk","b".repeat(64),"192.168.1.21",80);assertEquals(own,loaded.designFor(ID));
        loaded.setDesign(ID,null);assertEquals(loaded.defaultDesign(),loaded.designFor(ID));assertFalse(loaded.get(ID).overridden());
        loaded.configureDesign(custom);assertEquals(custom,new ClientRegistry(dir.toString(),json,Clock.systemUTC()).designFor(ID));
    }
    @Test void version2RegistryIsMigratedWithoutChangingWhatDevicesShow(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("clients.json"),"""
            {"internalUrl":"http://192.168.1.10:10002","externalUrl":"","pollSeconds":45,"emoji":"","scheme":"classic_mac",
             "weather":{"name":"Cologne, DE","latitude":50.94,"longitude":6.96},
             "layout":{"order":["status","second","first"],"first":["codex"],"second":["claude","codex"],"moodLeft":true},
             "clients":[
              {"identifier":"vibedock-inherits","name":"A","keyHash":"h","ip":"192.168.1.20","port":80,"pairedAt":1,"lastPullAt":null,"lastPullIp":null,
               "emoji":null,"scheme":null,"weather":null},
              {"identifier":"vibedock-fields","name":"B","keyHash":"h","ip":"192.168.1.21","port":80,"pairedAt":1,"lastPullAt":5,"lastPullIp":"192.168.1.22",
               "emoji":"-","scheme":"sunset","weather":{"name":"-","latitude":0,"longitude":0}},
              {"identifier":"vibedock-snapshot","name":"C","keyHash":"h","ip":"192.168.1.23","port":80,"pairedAt":1,"lastPullAt":null,"lastPullIp":null,
               "emoji":"","scheme":"","weather":null,"design":{"emoji":"🚀","scheme":"SAND","weather":null,
               "layout":{"order":["first","second","status"],"first":["claude"],"second":["codex","claude"],"moodLeft":false}}}]}
            """);
        var registry=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        var defaults=registry.defaultDesign();
        assertEquals(Scheme.CLASSIC_MAC,defaults.scheme());assertEquals("🤖",defaults.mood());assertEquals("Cologne, DE",defaults.weather().name());
        assertEquals(new Layout(List.of(new Layout.Context(true),new Layout.Usage(List.of("claude","codex")),new Layout.Usage(List.of("codex")))),defaults.layout());
        assertEquals(45,registry.pollSeconds());assertEquals("http://192.168.1.10:10002",registry.internalUrl());
        assertFalse(registry.get("vibedock-inherits").overridden());assertEquals(defaults,registry.designFor("vibedock-inherits"));
        var fields=registry.designFor("vibedock-fields");
        assertTrue(registry.get("vibedock-fields").overridden());assertEquals(new Design(Scheme.SUNSET,"",null,defaults.layout()),fields);
        assertEquals("192.168.1.22",registry.get("vibedock-fields").ip());
        assertEquals(new Design(Scheme.SAND,"🚀",null,new Layout(List.of(new Layout.Usage(List.of("claude")),new Layout.Usage(List.of("codex","claude")),new Layout.Context(false)))),
                registry.designFor("vibedock-snapshot"));
        assertTrue(Files.exists(dir.resolve("clients.json.v2.bak")));
        assertEquals(3,json.readTree(dir.resolve("clients.json").toFile()).path("version").asInt());
        var reloaded=new ClientRegistry(dir.toString(),json,Clock.systemUTC());
        assertEquals(fields,reloaded.designFor("vibedock-fields"));assertEquals(defaults,reloaded.defaultDesign());
    }
    @Test void rejectsInvalidBandsAndUnknownSources(){
        assertThrows(IllegalArgumentException.class,()->new Layout(List.of(new Layout.Usage(List.of("codex")),new Layout.Usage(List.of("claude")),new Layout.Usage(List.of("codex")))));
        assertThrows(IllegalArgumentException.class,()->new Layout.Usage(List.of()));
        assertThrows(IllegalArgumentException.class,()->new Layout.Usage(List.of("codex","claude","extra")));
        assertThrows(IllegalArgumentException.class,()->CUSTOM.validateSources(Set.of("codex")));
        CUSTOM.validateSources(Set.of("codex","claude"));
        assertEquals(layout(List.of(1,0,2),true),CUSTOM.withContextAtBottom());
        assertThrows(IllegalArgumentException.class,()->Scheme.parse("neon"));
    }
    @Test void everyDisplayTypeGetsAnImageInItsResolutionWithTheBandsThatFit() throws Exception {
        var renderer=new StatusImage("Europe/Berlin");
        assertEquals(2,DisplayType.M1.usageBands());assertTrue(DisplayType.M1.halfWidth());
        assertEquals(1,DisplayType.of("128x160").usageBands());assertFalse(DisplayType.of("135x240").halfWidth());
        assertEquals(DisplayType.M1,DisplayType.of("unknown"));assertEquals("320x240",DisplayType.reported("320x240").orElseThrow().id());
        assertTrue(DisplayType.reported("999x999").isEmpty());
        // Fitting keeps the band order, drops usage bands that do not fit and splits nothing on narrow screens.
        var fitted=CUSTOM.fit(DisplayType.of("128x160"));
        assertEquals(2,fitted.bands().size());assertInstanceOf(Layout.Context.class,fitted.bands().get(0));
        assertEquals(List.of("claude"),((Layout.Usage)fitted.bands().get(1)).sources());
        assertEquals(CUSTOM,CUSTOM.fit(DisplayType.M1));
        // A fourth line: three usage bands on 320 px high displays, the first two elsewhere.
        var four=new Layout(List.of(new Layout.Usage(List.of("codex")),new Layout.Usage(List.of("claude")),new Layout.Usage(List.of("codex","claude")),new Layout.Context(false)));
        assertEquals(3,DisplayType.of("240x320").usageBands());assertEquals(2,DisplayType.of("240x280").usageBands());
        assertEquals(four,four.fit(DisplayType.of("240x320")));assertEquals(3,four.fit(DisplayType.M1).bands().size());
        var u=new Layout.Usage(List.of("codex"));var c=new Layout.Usage(List.of("claude"),true);
        assertThrows(IllegalArgumentException.class,()->new Layout(List.of(u,u,u,u,u,new Layout.Context(false))));
        assertThrows(IllegalArgumentException.class,()->new Layout.Usage(List.of("codex","claude"),true));
        // Compact bands: one full and two compact or four compact bands fit the VibeTV M1, a third full one does not.
        var mixed=new Layout(List.of(u,c,c,new Layout.Context(false)));var compact=new Layout(List.of(c,c,c,c,new Layout.Context(true)));
        assertEquals(mixed,mixed.fit(DisplayType.M1));assertEquals(compact,compact.fit(DisplayType.M1));
        assertEquals(new Layout(List.of(u,u,new Layout.Context(false))),new Layout(List.of(u,u,c,new Layout.Context(false))).fit(DisplayType.M1));
        assertEquals(new Layout(List.of(u,new Layout.Context(false))),mixed.fit(DisplayType.of("128x160")));assertEquals(new Layout(List.of(c,c,new Layout.Context(true))),compact.fit(DisplayType.of("128x160")));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("{\"type\":\"usage\",\"sources\":[\"codex\"]}",json.writerFor(Layout.Band.class).writeValueAsString(u));
        assertEquals(c,json.readValue("{\"type\":\"usage\",\"sources\":[\"claude\"],\"compact\":true}",Layout.Band.class));
        for(var type:DisplayType.INVENTORY)for(var scheme:Scheme.values()){
            var image=renderer.render(snapshot(1),"192.168.1.50",new StatusImage.Look(icon(44),scheme,icon(24),"13°",CUSTOM),type);
            assertEquals(type.width()*type.height()*2,image.rgb565().length,type.id());
            var png=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image.png()));
            assertEquals(type.width(),png.getWidth());assertEquals(type.height(),png.getHeight());
            for(var layout:List.of(four,mixed,compact))
                assertEquals(type.width()*type.height()*2,renderer.render(snapshot(1),"192.168.1.50",new StatusImage.Look(icon(44),scheme,icon(24),"13°",layout),type).rgb565().length);
        }
        // The M1 keeps exactly the image it had before display types existed.
        var look=new StatusImage.Look(icon(44),Scheme.MIDNIGHT,null,null,Layout.DEFAULT);
        assertArrayEquals(renderer.render(snapshot(0),"192.168.1.50",look).rgb565(),renderer.render(snapshot(0),"192.168.1.50",look,DisplayType.M1).rgb565());
    }
}
