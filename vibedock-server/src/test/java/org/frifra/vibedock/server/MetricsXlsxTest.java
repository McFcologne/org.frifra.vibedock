package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

class MetricsXlsxTest {
    private static final String FULL="1;3600;eb;e5;a5;76;e;e;-3a;3;18a;60;3000;600";
    private static Map<String,String> unzip(byte[] bytes) throws Exception {
        var files=new LinkedHashMap<String,String>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
            for(ZipEntry e;(e=zip.getNextEntry())!=null;)files.put(e.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        return files;
    }
    private static MetricsXlsx.Entry entry(long at,String name){
        var s=DeviceMetrics.parse(FULL,at,"0000beef");
        return new MetricsXlsx.Entry(s,new MetricsCsv.Context(name,"0.6.1","power-on"));
    }
    @Test void aWorkbookHasInfoAndOneSheetPerDisplayWithRealValues() throws Exception {
        long at=java.time.Instant.parse("2026-11-05T16:38:07Z").toEpochMilli();
        var out=new ByteArrayOutputStream();
        MetricsXlsx.write(out,List.of(new MetricsXlsx.Sheet("vibedock-0a1b2c000001","=1+1",List.of(entry(at,"=1+1"))),new MetricsXlsx.Sheet("vibedock-0a1b2c000002","Kitchen: shelf/left",List.of())),
                ZoneId.of("Europe/Berlin"),true,"all");
        var files=unzip(out.toByteArray());
        assertEquals(List.of("[Content_Types].xml","_rels/.rels","xl/workbook.xml","xl/_rels/workbook.xml.rels","xl/styles.xml","xl/worksheets/sheet1.xml","xl/worksheets/sheet2.xml","xl/worksheets/sheet3.xml"),new ArrayList<>(files.keySet()));
        var book=files.get("xl/workbook.xml");
        assertTrue(book.contains("name=\"Info\"")&&book.contains("name=\"=1+1\"")&&book.contains("name=\"Kitchen  shelf left\""),book);
        var sheet=files.get("xl/worksheets/sheet2.xml");
        // 2026-11-05 16:38:07 UTC as an Excel date; the local time is one hour later in Berlin.
        assertTrue(sheet.contains("<c r=\"A2\" s=\"2\"><v>46331.69313"),sheet);
        assertTrue(sheet.contains("<c r=\"B2\" s=\"2\"><v>46331.73480"),sheet);
        assertTrue(sheet.contains("<c r=\"D2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">=1+1</t></is></c>"),"a name that looks like a formula stays text");
        assertTrue(sheet.contains("<c r=\"I2\" s=\"3\"><v>23.5</v></c>"),"free memory in kB as a number");
        assertTrue(sheet.contains("<c r=\"H2\"><v>3600</v></c>"),"uptime as a number");
        assertTrue(sheet.contains("<autoFilter ref=\"A1:U2\"/>")&&sheet.contains("state=\"frozen\""));
        assertTrue(sheet.contains(">raw</t>")&&sheet.contains(FULL));
        assertTrue(files.get("xl/worksheets/sheet3.xml").contains("<autoFilter ref=\"A1:U1\"/>"),"an empty sheet keeps its header");
    }
    @Test void anUnmeasuredValueIsAnEmptyCell() throws Exception {
        var s=DeviceMetrics.parse("1;5;;;;;;;;0;;;0;0",1_000,null);
        var out=new ByteArrayOutputStream();
        MetricsXlsx.write(out,List.of(new MetricsXlsx.Sheet("vibedock-0a1b2c000003","x",List.of(new MetricsXlsx.Entry(s,new MetricsCsv.Context("","",""))))),ZoneId.of("UTC"),false,"all");
        var sheet=unzip(out.toByteArray()).get("xl/worksheets/sheet2.xml");
        assertTrue(sheet.contains("<c r=\"H2\"><v>5</v></c>")&&!sheet.contains("r=\"I2\""),sheet);
        assertFalse(sheet.contains(">raw</t>"));
    }
    @Test void sheetNamesAreValidShortAndUnique(){
        var names=MetricsXlsx.sheetNames(List.of(new MetricsXlsx.Sheet("vibedock-0a1b2c000001","Info",List.of()),new MetricsXlsx.Sheet("vibedock-0a1b2c000002","Info",List.of()),
                new MetricsXlsx.Sheet("vibedock-0a1b2c000003","x".repeat(40),List.of()),new MetricsXlsx.Sheet("vibedock-0a1b2c000004","",List.of()),new MetricsXlsx.Sheet("vibedock-0a1b2c000005","'a'",List.of())));
        assertEquals(List.of("Info (2)","Info (3)","x".repeat(31),"vibedock-0a1b2c000004","a"),names);
        assertEquals("a &amp; &lt;b&gt; c",MetricsXlsx.escape("a & <b>\u0001c"));
    }
}
