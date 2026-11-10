package org.frifra.vibedock.server;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The samples as readable CSV: comma as separator, dot as decimal mark, the unit in the column header, an empty cell
 * for a value the display did not measure, times in UTC and local (ISO 8601). Memory is in kB (1000 bytes) with the
 * display's resolution of 0.1 kB, the picture time in seconds with 0.1 s. With raw, the header as received follows.
 */
final class MetricsCsv {
    private MetricsCsv(){}
    /**
     * What the export adds to a sample: the display's name and the firmware and start reason of its start, and the
     * smoothed battery voltage of the sample (null without one).
     */
    record Context(String name,String firmware,String reason,BatterySeries.Point battery) {
        Context(String name,String firmware,String reason){this(name,firmware,reason,null);}
    }
    static final List<String> COLUMNS=List.of("time_utc","time_local","device","name","firmware","restart_reason","boot","uptime_s","free_kB","lowest_free_kB",
            "largest_block_kB","free_after_tls_kB","tls_stack_kB","picture_s","wifi_rssi_dBm","wifi_drops","battery_mV","brightness_pct","active_s","dimmed_s",
            "battery_smoothed_mV","battery_slope_mV_per_h","battery_phase");
    static String header(boolean raw){return String.join(",",COLUMNS)+(raw?",raw":"")+"\r\n";}
    static void row(StringBuilder out,String device,DeviceMetrics.Sample s,Context context,ZoneId zone,boolean raw){
        var at=Instant.ofEpochMilli(s.at());
        var cells=new ArrayList<String>();
        cells.add(DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        cells.add(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).atZone(zone)));
        cells.add(cell(device));cells.add(cell(context.name()));cells.add(cell(context.firmware()));cells.add(cell(context.reason()));cells.add(cell(s.boot()));
        cells.add(text(s.up()));cells.add(tenths(s.freeBytes(),1000));cells.add(tenths(s.lowestBytes(),1000));cells.add(tenths(s.blockBytes(),1000));
        cells.add(tenths(s.afterTlsBytes(),1000));cells.add(tenths(s.tlsStackBytes(),1000));cells.add(tenths(s.pictureMs(),1000));
        cells.add(text(s.rssi()));cells.add(text(s.drops()));cells.add(text(s.millivolts()));cells.add(text(s.brightness()));
        cells.add(text(s.activeSeconds()));cells.add(text(s.dimmedSeconds()));
        var b=context.battery();
        cells.add(b==null||b.smoothed()==null?"":String.format(Locale.ROOT,"%.0f",b.smoothed()));
        cells.add(b==null||b.slope()==null?"":String.format(Locale.ROOT,"%.0f",b.slope()));
        cells.add(b==null||b.smoothed()==null?"":b.phase());
        if(raw)cells.add(cell(s.raw()));
        out.append(String.join(",",cells)).append("\r\n");
    }
    static String text(Number n){return n==null?"":n.toString();}
    static String tenths(Integer value,int unit){return value==null?"":String.format(Locale.ROOT,"%.1f",value/(double)unit);}
    /** Characters that start a formula in a spreadsheet (after leading white space; a tab or a carriage return at the start counts too). */
    private static final String FORMULA_START="=+-@\t\r";
    /** A text cell (names and the like, which a user can choose): quoted when needed and never the start of a spreadsheet formula. Never null. */
    static String cell(String value){
        if(value==null)return "";
        String stripped=value.stripLeading();
        boolean formula=!stripped.isEmpty()&&FORMULA_START.indexOf(stripped.charAt(0))>=0||!value.isEmpty()&&FORMULA_START.indexOf(value.charAt(0))>=0;
        String text=formula?"'"+value:value;
        return text.contains(",")||text.contains("\"")||text.contains("\n")||text.contains("\r")?"\""+text.replace("\"","\"\"")+"\"":text;
    }
}
