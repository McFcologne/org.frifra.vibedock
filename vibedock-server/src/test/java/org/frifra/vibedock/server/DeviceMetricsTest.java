package org.frifra.vibedock.server;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The measured values of displays: header, kept samples every 5 minutes, key figures, export and pages. */
@SpringBootTest(properties={"vibedock.admin-auth=none","vibedock.admin-password=","vibedock.polling=false"})
@AutoConfigureMockMvc
class DeviceMetricsTest {
    static Path data;
    @DynamicPropertySource static void directory(DynamicPropertyRegistry r) throws Exception {
        data=Files.createTempDirectory("vibedock-metrics-");r.add("vibedock.data-dir",data::toString);
        r.add("vibedock.log-dir",Files.createDirectories(data.resolve("logs"))::toString);
    }
    @Autowired MockMvc mvc;@Autowired ClientRegistry clients;@Autowired DeviceMetrics liveMetrics;@Autowired DeviceDiagnostics liveDiagnostics;
    /** The administration listens on its own port. */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b){return b.with(r->{r.setLocalPort(10001);return r;});}
    private final ObjectMapper json=JsonCompatibility.mapper();
    private static final class TestClock extends Clock {
        long now=Instant.parse("2026-11-05T10:00:00Z").toEpochMilli();
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    private static final String FULL="1;3600;eb;e5;a5;76;e;e;-3a;3;18a;60;3000;600";
    private static String pull(int up,int lowestHex){return "1;"+up+";f0;"+Integer.toHexString(lowestHex)+";a0;;;;-3a;0;;100;"+up+";0";}

    @Test void theHeaderIsReadInItsUnitsAndAnythingInvalidIsLeftOut(){
        var s=DeviceMetrics.parse(FULL,1000,"0000beef");
        assertEquals(3600L,s.up());assertEquals(23500,s.freeBytes());assertEquals(22900,s.lowestBytes());assertEquals(16500,s.blockBytes());
        assertEquals(11800,s.afterTlsBytes());assertEquals(1400,s.tlsStackBytes());assertEquals(1400,s.pictureMs());assertEquals(-58,s.rssi());
        assertEquals(3,s.drops());assertEquals(3940,s.millivolts());assertEquals(60,s.brightness());assertEquals(3000L,s.activeSeconds());assertEquals(600L,s.dimmedSeconds());
        assertEquals("0000beef",s.boot());
        // Empty fields were not measured; a signal of 0 dBm and a missing battery are told apart.
        var none=DeviceMetrics.parse("1;5;;;;;;;;0;;;0;0",1,null);
        assertEquals(5L,none.up());assertNull(none.freeBytes());assertNull(none.rssi());assertNull(none.millivolts());assertNull(none.brightness());assertNull(none.boot());
        // A later version appends fields: they are ignored. A shorter header leaves the missing ones out.
        assertEquals(-58,DeviceMetrics.parse(FULL+";ff;ee",1,null).rssi());
        assertNull(DeviceMetrics.parse("1;7;a",1,null).rssi());
        // Values out of range are left out, the rest stays.
        var bad=DeviceMetrics.parse("1;9;zz;1000000000;a0;;;;5;0;1;999;1;1",1,"nothex");
        assertNull(bad.freeBytes());assertNull(bad.lowestBytes());assertEquals(16000,bad.blockBytes());assertNull(bad.rssi());assertNull(bad.millivolts());assertNull(bad.brightness());assertNull(bad.boot());
        // Not version 1, no run time, too long, or nothing: no sample.
        for(String h:new String[]{null,"","2;5;a","1","1;x;a","a=b;c=d","1;5;"+"a".repeat(300)})assertNull(DeviceMetrics.parse(h,1,null),String.valueOf(h));
    }
    @Test void oneSampleEveryFiveMinutesKeepsTheLowestOfTheMinutesBetween(@TempDir Path dir){
        var clock=new TestClock();var metrics=new DeviceMetrics(dir.toString(),json,clock);String id="vibedock-0a1b2c000020",boot="0000beef";
        metrics.report(id,pull(10,0x200),boot);                         // kept: the first
        for(int i=1;i<=4;i++){
            clock.now+=60_000;
            metrics.report(id,pull(10+60*i,i==2?0x80:0x200),boot);   // not kept, but its dip counts
        }
        clock.now+=60_000;metrics.report(id,pull(310,0x200),boot);   // kept: 5 minutes after the first
        var kept=metrics.samples(id,0,Long.MAX_VALUE);
        assertEquals(2,kept.size());
        assertEquals(12800,kept.get(1).lowestBytes(),"the dip between the samples is not lost");
        assertEquals(310L,kept.get(1).up());
        // A new start is kept at once; invalid headers and bad identifiers are not.
        clock.now+=10_000;metrics.report(id,pull(3,0x200),"0000cafe");
        metrics.report(id,"junk","0000cafe");metrics.report("short",pull(3,0x200),boot);
        assertEquals(3,metrics.samples(id,0,Long.MAX_VALUE).size());
        // The period filter, and a restart of the server continues with the window from the file.
        assertEquals(1,metrics.samples(id,clock.now-1000,clock.now+1).size());
        var again=new DeviceMetrics(dir.toString(),json,clock);
        clock.now+=60_000;again.report(id,pull(70,0x200),"0000cafe");
        assertEquals(3,again.samples(id,0,Long.MAX_VALUE).size(),"60 s after the last kept sample: not kept");
        assertEquals(0,metrics.samples("vibedock-0a1b2c000021",0,Long.MAX_VALUE).size());
    }
    @Test void theMetricsPagePagesBackByWholeWindowsWithinTheRetention() throws Exception {
        String id="vibedock-0a1b2c000090";
        mvc.perform(admin(get("/admin/clients/"+id+"/metrics").param("hours","720")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Older")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Newer"))));
        mvc.perform(admin(get("/admin/clients/"+id+"/metrics").param("hours","720").param("back","1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Newer"))).andExpect(content().string(org.hamcrest.Matchers.containsString("Older")));
        mvc.perform(admin(get("/admin/clients/"+id+"/metrics").param("hours","720").param("back","3")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Older")))).andExpect(content().string(org.hamcrest.Matchers.containsString("Newer")));
        mvc.perform(admin(get("/admin/clients/"+id+"/metrics").param("hours","24").param("back","-5"))).andExpect(status().isOk());
    }
    @Test void anEarlierSingleFileIsSplitIntoDaysAndOldDaysAreDeleted(@TempDir Path dir) throws Exception {
        var clock=new TestClock();var metrics=new DeviceMetrics(dir.toString(),json,clock,()->30);String id="vibedock-0a1b2c000022";
        Path folder=Files.createDirectories(dir.resolve("device-logs").resolve(id)),file=folder.resolve(DeviceLogs.METRICS);
        long old=clock.now-31*86_400_000L,recent=clock.now-2*86_400_000L;
        Files.writeString(file,"{\"at\":"+old+",\"boot\":null,\"raw\":\""+FULL+"\",\"lowest\":null}\nnot json\n{\"at\":"+recent+",\"boot\":null,\"raw\":\""+FULL+"\",\"lowest\":null}\n");
        metrics.report(id,FULL,"0000beef");
        var kept=metrics.samples(id,0,Long.MAX_VALUE);
        assertEquals(2,kept.size(),"the sample older than 30 days and the unreadable line are gone");assertEquals(recent,kept.get(0).at());
        assertFalse(Files.exists(file),"the single file is gone");
        try(var days=Files.list(folder)){assertTrue(days.allMatch(f->f.getFileName().toString().matches("metrics-\\d{4}-\\d{2}-\\d{2}\\.jsonl")));}
    }
    @Test void aMigrationThatWasCutOffDoesNotWriteSamplesTwice(@TempDir Path dir) throws Exception {
        var clock=new TestClock();String id="vibedock-0a1b2c000024";
        Path folder=Files.createDirectories(dir.resolve("device-logs").resolve(id));
        long first=clock.now-3_600_000L,second=clock.now-1_800_000L;
        String l1="{\"at\":"+first+",\"boot\":null,\"raw\":\""+FULL+"\",\"lowest\":null}",l2="{\"at\":"+second+",\"boot\":null,\"raw\":\""+FULL+"\",\"lowest\":null}";
        Files.writeString(folder.resolve(DeviceLogs.METRICS),l1+"\n"+l2+"\n");
        // The day file already holds the first sample: the server stopped before the single file was removed.
        String day=java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(first).atZone(ZoneOffset.UTC));
        Files.writeString(folder.resolve("metrics-"+day+".jsonl"),l1+"\n");
        var metrics=new DeviceMetrics(dir.toString(),json,clock);
        var kept=metrics.samples(id,0,Long.MAX_VALUE);
        assertEquals(2,kept.size());assertEquals(first,kept.get(0).at());assertEquals(second,kept.get(1).at());
        assertFalse(Files.exists(folder.resolve(DeviceLogs.METRICS)));
        // And a second run (a restart with the single file still there) changes nothing.
        Files.writeString(folder.resolve(DeviceLogs.METRICS),l1+"\n"+l2+"\n");
        assertEquals(2,new DeviceMetrics(dir.toString(),json,clock).samples(id,0,Long.MAX_VALUE).size());
    }
    @Test void daysBeyondTheRetentionAreDeletedAndTheRangeReadsOnlyItsDays(@TempDir Path dir) throws Exception {
        var clock=new TestClock();int[] days={90};
        var metrics=new DeviceMetrics(dir.toString(),json,clock,()->days[0]);String id="vibedock-0a1b2c000023";
        Path folder=Files.createDirectories(dir.resolve("device-logs").resolve(id));
        var day=java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
        for(int back:new int[]{100,60,5}){
            long at=clock.now-back*86_400_000L;
            Files.writeString(folder.resolve("metrics-"+day.format(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC))+".jsonl"),
                    "{\"at\":"+at+",\"boot\":null,\"raw\":\""+FULL+"\",\"lowest\":null}\n");
        }
        assertEquals(3,metrics.samples(id,0,Long.MAX_VALUE).size(),"nothing is deleted before the next write");
        metrics.report(id,FULL,"0000beef");
        try(var files=Files.list(folder)){assertEquals(3,files.count(),"the day 100 days back is deleted, 60, 5 and today stay");}
        assertEquals(3,metrics.samples(id,0,Long.MAX_VALUE).size(),"60 and 5 days back and now");
        assertEquals(1,metrics.samples(id,clock.now-61*86_400_000L,clock.now-59*86_400_000L).size());
        assertEquals(90,metrics.keepDays());days[0]=30;assertEquals(30,metrics.keepDays());
    }
    @Test void csvHasUnitsInTheHeaderEmptyCellsAndDotDecimals(){
        var s=DeviceMetrics.parse(FULL,Instant.parse("2026-11-05T16:38:07Z").toEpochMilli(),"0000beef");
        var out=new StringBuilder(MetricsCsv.header(true));
        MetricsCsv.row(out,"vibedock-0a1b2c000030",s,new MetricsCsv.Context("=Kitchen, shelf","0.4.3","power-on"),ZoneId.of("Europe/Berlin"),true);
        MetricsCsv.row(out,"vibedock-0a1b2c000030",DeviceMetrics.parse("1;5;;;;;;;;0;;;0;0",1_000,null),new MetricsCsv.Context("","",""),ZoneOffset.UTC,true);
        var lines=out.toString().split("\r\n");
        assertEquals("time_utc,time_local,device,name,firmware,restart_reason,boot,uptime_s,free_kB,lowest_free_kB,largest_block_kB,free_after_tls_kB,tls_stack_kB,picture_s,wifi_rssi_dBm,wifi_drops,battery_mV,brightness_pct,active_s,dimmed_s,raw",lines[0]);
        assertEquals("2026-11-05T16:38:07Z,2026-11-05T17:38:07+01:00,vibedock-0a1b2c000030,\"'=Kitchen, shelf\",0.4.3,power-on,0000beef,3600,23.5,22.9,16.5,11.8,1.4,1.4,-58,3,3940,60,3000,600,"+FULL,lines[1]);
        assertEquals("1970-01-01T00:00:01Z,1970-01-01T00:00:01Z,vibedock-0a1b2c000030,,,,,5,,,,,,,,0,,,0,0,1;5;;;;;;;;0;;;0;0",lines[2]);
        assertFalse(MetricsCsv.header(false).contains("raw"));
    }
    @Test void textCellsNeverStartAFormulaButNumbersStayNumbers(){
        for(String name:new String[]{"=1+1","+1","-1+cmd|' /C calc'!A0","@SUM(A1)","\t=1"," =1","  -2","\r=1","\n=1 ","\t"})
            assertTrue(MetricsCsv.cell(name).replace("\"","").startsWith("'"),"neutralised: "+name.replace("\t","<tab>").replace("\r","<cr>").replace("\n","<lf>"));
        for(String name:new String[]{"Kitchen","a-b","1-2","","x=y"," ok"})
            assertFalse(MetricsCsv.cell(name).startsWith("'"),"left alone: "+name);
        assertEquals("",MetricsCsv.cell(null));
        var out=new StringBuilder();
        MetricsCsv.row(out,"vibedock-0a1b2c000030",DeviceMetrics.parse(FULL,1_000,"0000beef"),new MetricsCsv.Context("-1+cmd","0.4.3","power-on"),ZoneOffset.UTC,false);
        assertTrue(out.toString().contains(",\"'-1+cmd\"")||out.toString().contains(",'-1+cmd,"),out.toString());
        assertTrue(out.toString().contains(",-58,"),"the signal stays a number");
    }
    @Test void keyFiguresAlsoCountWhatTheSamplesMeasured(){
        var samples=List.of(DeviceMetrics.parse("1;10;f0;50;a0;60;20;20;-32;0;;;1;0",1,null),DeviceMetrics.parse("1;70;f0;a0;a0;70;10;30;-46;0;;;1;0",2,null));
        var withLog=DeviceLogTimeline.figures(List.of("+3s Wi-Fi X: 192.168.1.2, -60 dBm, heap 20000"),samples);
        assertEquals(8000,withLog.lowestHeap());assertEquals(9600,withLog.lowestAfterTls());assertEquals(3200,withLog.highestStack());assertEquals(4800,withLog.slowestPicture());
        assertEquals(-70,withLog.weakestSignal());assertEquals(-50,withLog.strongestSignal());
        assertEquals(DeviceLogTimeline.figures(List.of("x")),DeviceLogTimeline.figures(List.of("x"),List.of()));
    }
    @Test void theSupportFileCarriesTheSamplesAsRows(){
        var s=DeviceMetrics.parse(FULL,Instant.parse("2026-11-05T16:38:07Z").toEpochMilli(),"0000beef");
        var samples=SupportFile.Samples.of("vibedock-0a1b2c000031",List.of(s));
        assertEquals(SupportFile.Samples.COLUMNS.size(),samples.rows().get(0).size());
        assertEquals("2026-11-05T16:38:07Z",samples.rows().get(0).get(0));assertEquals(23500,samples.rows().get(0).get(3));assertEquals(-58,samples.rows().get(0).get(9));
    }
    @Test void thePullKeepsTheSampleAndThePageAndTheExportShowIt() throws Exception {
        String id="vibedock-0a1b2c000040";
        clients.paired(id,"Metric shelf","ab".repeat(32),"192.168.1.60",80,"vibedock-m1");
        liveDiagnostics.report(id,"boot=0000beef;up=60;heap=20000;min=19000;rssi=-58;drops=0;start=power-on;reset=1","0.4.3","vibedock-m1");
        liveMetrics.report(id,FULL,"0000beef");
        mvc.perform(admin(get("/admin/clients/"+id+"/metrics"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<svg class=\"metric-chart\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Metrics of Metric shelf")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<td>23.5</td>")));
        mvc.perform(admin(get("/admin/clients/"+id+"/metrics").param("hours","7"))).andExpect(status().isOk());
        var body=mvc.perform(admin(get("/admin/metrics.csv").param("device",id).param("raw","true"))).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsString();
        var lines=body.split("\r\n");
        assertEquals(2,lines.length);assertTrue(lines[1].contains(",power-on,0000beef,3600,23.5,22.9,"),lines[1]);assertTrue(lines[1].endsWith(FULL));
        mvc.perform(admin(get("/admin/metrics.csv").param("device",id).param("from","2999-01-01"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(id))));
        mvc.perform(admin(get("/admin/metrics.csv").param("from","yesterday"))).andExpect(status().isBadRequest());
    }
    @Test void theChartMarksGapsWithoutSamplesAsOffline(){
        long t0=Instant.parse("2026-11-05T08:00:00Z").toEpochMilli(),step=DeviceMetrics.INTERVAL_SECONDS*1000L;
        var samples=new ArrayList<DeviceMetrics.Sample>();
        for(int i=0;i<4;i++)samples.add(sample(t0+i*step,-50));
        for(int i=0;i<4;i++)samples.add(sample(t0+(20+i)*step,-60));   // 16 intervals without a sample in between
        var series=List.of(new MetricsChart.Series("Signal",DeviceMetrics.Sample::rssi));
        String svg=MetricsChart.svg(samples,t0,t0+30*step,series,1,false,"dBm",ZoneOffset.UTC);
        assertEquals(2,svg.split("class=\"offline\"",-1).length-1,"the gap in between and the time after the last sample");
        assertTrue(svg.contains(">Offline</text>"));
        var dense=new ArrayList<DeviceMetrics.Sample>();
        for(int i=0;i<=24;i++)dense.add(sample(t0+i*step,-50));
        assertFalse(MetricsChart.svg(dense,t0,t0+24*step,series,1,false,"dBm",ZoneOffset.UTC).contains("class=\"offline\""));
    }
    private static DeviceMetrics.Sample sample(long at,int rssi){
        return new DeviceMetrics.Sample(at,"0000beef","",1L,null,null,null,null,null,null,rssi,0,null,null,null,null);
    }
    @Test void theCardPopupShowsTheChartsAndLinksToThePage() throws Exception {
        String id="vibedock-0a1b2c000041";
        clients.paired(id,"Popup shelf","ab".repeat(32),"192.168.1.61",80,"vibedock-m1");
        liveMetrics.report(id,FULL,"0000beef");
        mvc.perform(admin(get("/admin/clients/"+id+"/metrics/popup"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<svg class=\"metric-chart\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/clients/"+id+"/metrics?hours=24")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<html"))));
        mvc.perform(admin(get("/admin/devices"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-metrics-url=\"/admin/clients/"+id+"/metrics/popup\"")));
    }
}
