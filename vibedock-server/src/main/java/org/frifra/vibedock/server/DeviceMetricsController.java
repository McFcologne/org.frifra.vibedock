package org.frifra.vibedock.server;

import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** The measured values of displays (DeviceMetrics): curves and a table per display, and the export as CSV. */
@Controller
class DeviceMetricsController {
    /** Periods the page offers, in hours. */
    static final List<Integer> PERIODS=List.of(6,24,72,168,720);
    private final ClientRegistry registry;private final DeviceMetrics metrics;private final DeviceDiagnostics diagnostics;private final Clock clock;
    DeviceMetricsController(ClientRegistry registry,DeviceMetrics metrics,DeviceDiagnostics diagnostics,Clock clock){
        this.registry=registry;this.metrics=metrics;this.diagnostics=diagnostics;this.clock=clock;
    }
    private static final int TABLE_ROWS=200;
    /** The popup of a device card: 6 h when the last 6 h hold enough samples to show a course, otherwise 24 h. */
    static final int POPUP_MIN_SAMPLES=12;
    @GetMapping("/admin/clients/{identifier}/metrics/popup") String popup(@PathVariable String identifier,Model model) {
        long now=clock.millis();
        int period=metrics.samples(identifier,now-6*3_600_000L,now+1).size()>=POPUP_MIN_SAMPLES?6:24;
        fill(identifier,period,0,model);
        return "device-metrics-popup :: div.metrics-popup";
    }
    @GetMapping("/admin/clients/{identifier}/metrics") String page(@PathVariable String identifier,@RequestParam(defaultValue="24") int hours,@RequestParam(defaultValue="0") int back,Model model) {
        fill(identifier,PERIODS.contains(hours)?hours:24,Math.max(0,Math.min(back,100_000)),model);
        return "device-metrics";
    }
    private void fill(String identifier,int period,int back,Model model) {
        String name=identifier;
        try{name=registry.get(identifier).name();}catch(IllegalArgumentException notPaired){ /* samples of a display that is not paired any more */ }
        long now=clock.millis(),span=period*3_600_000L,to=now-back*span,from=to-span;
        long oldest=now-metrics.keepDays()*86_400_000L;
        var samples=metrics.samples(identifier,from,to+1);
        ZoneId zone=ZoneId.systemDefault();
        model.addAttribute("identifier",identifier);model.addAttribute("deviceName",name);
        model.addAttribute("periods",PERIODS);model.addAttribute("hours",period);
        model.addAttribute("count",samples.size());model.addAttribute("interval",DeviceMetrics.INTERVAL_SECONDS/60);model.addAttribute("keepDays",metrics.keepDays());
        model.addAttribute("back",back);model.addAttribute("hasOlder",from>oldest);model.addAttribute("hasNewer",back>0);
        var stamp=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        model.addAttribute("range",stamp.format(Instant.ofEpochMilli(from).atZone(zone))+" – "+stamp.format(Instant.ofEpochMilli(to).atZone(zone)));
        model.addAttribute("memory",MetricsChart.svg(samples,from,to,List.of(
                new MetricsChart.Series("Free",DeviceMetrics.Sample::freeBytes),new MetricsChart.Series("Lowest free",DeviceMetrics.Sample::lowestBytes),
                new MetricsChart.Series("Largest block",DeviceMetrics.Sample::blockBytes)),1000,true,"kB",zone));
        model.addAttribute("wifi",MetricsChart.svg(samples,from,to,List.of(new MetricsChart.Series("Signal",DeviceMetrics.Sample::rssi)),1,false,"dBm",zone));
        var smoothed=new HashMap<Long,Double>();
        for(var p:BatterySeries.of(samples))if(p.smoothed()!=null)smoothed.put(p.at(),p.smoothed());
        model.addAttribute("battery",MetricsChart.svg(samples,from,to,List.of(new MetricsChart.Series("Voltage",DeviceMetrics.Sample::millivolts),
                new MetricsChart.Series("Smoothed",sample->smoothed.get(sample.at()))),1,false,"mV",zone));
        var newest=new ArrayList<>(samples);Collections.reverse(newest);
        var rows=new ArrayList<List<String>>();
        for(var s:newest.subList(0,Math.min(TABLE_ROWS,newest.size()))){
            rows.add(List.of(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(s.at()).atZone(zone)),
                    MetricsCsv.tenths(s.freeBytes(),1000),MetricsCsv.tenths(s.lowestBytes(),1000),MetricsCsv.tenths(s.blockBytes(),1000),
                    MetricsCsv.tenths(s.afterTlsBytes(),1000),MetricsCsv.tenths(s.tlsStackBytes(),1000),MetricsCsv.tenths(s.pictureMs(),1000),
                    MetricsCsv.text(s.rssi()),MetricsCsv.text(s.drops()),MetricsCsv.text(s.millivolts()),MetricsCsv.text(s.brightness()),
                    s.activeSeconds()==null?"":s.activeSeconds()+" s",s.dimmedSeconds()==null?"":s.dimmedSeconds()+" s"));
        }
        model.addAttribute("rows",rows);
        model.addAttribute("shown",rows.size());
    }
    /**
     * All kept samples as CSV, oldest first per display. device limits it to one display (default: all paired
     * displays); from and to (a date yyyy-MM-dd in server time, or an instant; to a date includes that day) limit the
     * period; raw adds the header as received.
     */
    @GetMapping(value="/admin/metrics.csv",produces="text/csv") ResponseEntity<byte[]> csv(@RequestParam(required=false) String device,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(defaultValue="false") boolean raw) throws IOException {
        ZoneId zone=ZoneId.systemDefault();
        var out=new StringBuilder(MetricsCsv.header(raw));
        for(var sheet:export(device,bound(from,zone,false),bound(to,zone,true)))
            for(var e:sheet.entries())MetricsCsv.row(out,sheet.device(),e.sample(),e.context(),zone,raw);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+fileName(zone,"csv")+"\"")
                .contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(out.toString().getBytes(StandardCharsets.UTF_8));
    }
    /** The same samples as an Excel workbook (MetricsXlsx), one sheet per display; same parameters as the CSV. */
    @GetMapping(value="/admin/metrics.xlsx",produces=MetricsXlsx.CONTENT_TYPE) ResponseEntity<byte[]> xlsx(@RequestParam(required=false) String device,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(defaultValue="false") boolean raw) throws IOException {
        ZoneId zone=ZoneId.systemDefault();
        String period=(from==null||from.isBlank()?"first kept sample":from)+" to "+(to==null||to.isBlank()?"now":to);
        var out=new java.io.ByteArrayOutputStream();
        MetricsXlsx.write(out,export(device,bound(from,zone,false),bound(to,zone,true)),zone,raw,period);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+fileName(zone,"xlsx")+"\"")
                .contentType(MediaType.parseMediaType(MetricsXlsx.CONTENT_TYPE)).body(out.toByteArray());
    }
    private String fileName(ZoneId zone,String extension){return "vibedock-metrics-"+LocalDate.now(clock.withZone(zone))+"."+extension;}
    /** The samples of the chosen display (default: every paired display) with what the export adds to them. */
    private List<MetricsXlsx.Sheet> export(String device,long start,long end) throws IOException {
        var ids=new ArrayList<String>();
        if(device!=null&&!device.isBlank())ids.add(device.trim());
        else for(var v:registry.views())if(v.web()==null)ids.add(v.identifier());
        var sheets=new ArrayList<MetricsXlsx.Sheet>();
        for(String id:ids){
            String name="",current="";
            try{var view=registry.get(id);name=view.name();current=view.firmware()==null?"":view.firmware();}catch(IllegalArgumentException notPaired){ /* kept samples of a removed display */ }
            var starts=new HashMap<String,DeviceDiagnostics.Start>();
            for(var s:diagnostics.starts(id))starts.put(s.bootId(),s);
            var entries=new ArrayList<MetricsXlsx.Entry>();
            var kept=metrics.samples(id,start,end);var smoothed=BatterySeries.of(kept);
            for(int i=0;i<kept.size();i++){
                var s=kept.get(i);
                var begin=s.boot()==null?null:starts.get(s.boot());
                entries.add(new MetricsXlsx.Entry(s,new MetricsCsv.Context(name,begin!=null&&begin.firmware()!=null?begin.firmware():current,begin==null?"":begin.kind(),smoothed.get(i))));
            }
            sheets.add(new MetricsXlsx.Sheet(id,name,entries));
        }
        return sheets;
    }
    /** A period bound in epoch milliseconds; a missing one is open (0 or the end of time). */
    private static long bound(String text,ZoneId zone,boolean end){
        if(text==null||text.isBlank())return end?Long.MAX_VALUE:0;
        try{
            if(text.length()==10){var day=LocalDate.parse(text);return (end?day.plusDays(1):day).atStartOfDay(zone).toInstant().toEpochMilli();}
            return Instant.parse(text).toEpochMilli();
        }catch(DateTimeParseException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a date (yyyy-MM-dd) or an instant (2026-11-05T16:00:00Z)");}
    }
}
