package org.frifra.vibedock.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The samples as an Excel workbook (.xlsx, written by hand: a ZIP of a few XML files, no library): one sheet per
 * display with the columns of {@link MetricsCsv}, but as real values: times as dates, memory in kB and the picture time
 * in seconds as numbers, empty cells for what the display did not measure. Texts are stored as plain strings, so a
 * name that looks like a formula stays text. A first sheet "Info" names the period and the units.
 */
final class MetricsXlsx {
    private MetricsXlsx(){}
    static final String CONTENT_TYPE="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** Rows of a sheet at most (Excel's limit is 1,048,576 including the header); older samples are cut off. */
    static final int MAX_ROWS=1_048_575;
    record Entry(DeviceMetrics.Sample sample,MetricsCsv.Context context) {}
    record Sheet(String device,String name,List<Entry> entries) {}
    private static final int S_HEADER=1,S_TIME=2,S_TENTH=3;
    private static final double EXCEL_EPOCH_DAYS=25569;

    static void write(OutputStream out,List<Sheet> sheets,ZoneId zone,boolean raw,String period) throws IOException {
        var names=sheetNames(sheets);
        try(var zip=new ZipOutputStream(out,StandardCharsets.UTF_8)){
            put(zip,"[Content_Types].xml",contentTypes(sheets.size()+1));
            put(zip,"_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(zip,"xl/workbook.xml",workbook(names));
            put(zip,"xl/_rels/workbook.xml.rels",workbookRels(sheets.size()+1));
            put(zip,"xl/styles.xml",STYLES);
            put(zip,"xl/worksheets/sheet1.xml",info(sheets,zone,period));
            for(int i=0;i<sheets.size();i++)put(zip,"xl/worksheets/sheet"+(i+2)+".xml",sheet(sheets.get(i),zone,raw));
        }
    }
    private static void put(ZipOutputStream zip,String name,String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));zip.write(content.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    private static final String XML="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String MAIN="xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
    private static String contentTypes(int sheets){
        var b=new StringBuilder(XML).append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for(int i=1;i<=sheets;i++)b.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        return b.append("</Types>").toString();
    }
    private static String workbook(List<String> names){
        var b=new StringBuilder(XML).append("<workbook ").append(MAIN).append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        b.append("<sheet name=\"Info\" sheetId=\"1\" r:id=\"rId1\"/>");
        for(int i=0;i<names.size();i++)b.append("<sheet name=\"").append(escape(names.get(i))).append("\" sheetId=\"").append(i+2).append("\" r:id=\"rId").append(i+2).append("\"/>");
        return b.append("</sheets></workbook>").toString();
    }
    private static String workbookRels(int sheets){
        var b=new StringBuilder(XML).append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for(int i=1;i<=sheets;i++)b.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        b.append("<Relationship Id=\"rId").append(sheets+1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        return b.append("</Relationships>").toString();
    }
    /** 0 default, 1 bold header, 2 date and time, 3 one decimal. */
    private static final String STYLES=XML+"<styleSheet "+MAIN+"><numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"yyyy\\-mm\\-dd\\ hh:mm:ss\"/><numFmt numFmtId=\"165\" formatCode=\"0.0\"/></numFmts>"
            +"<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
            +"<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
            +"<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            +"<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            +"<cellXfs count=\"4\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
            +"<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/><xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";

    private static String info(List<Sheet> sheets,ZoneId zone,String period){
        var b=new StringBuilder(XML).append("<worksheet ").append(MAIN).append("><cols><col min=\"1\" max=\"1\" width=\"22\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"90\" customWidth=\"1\"/></cols><sheetData>");
        int r=1;
        r=infoRow(b,r,"VibeDock metrics","");
        r=infoRow(b,r,"Period",period);
        r=infoRow(b,r,"Local time zone",zone.getId());
        r=infoRow(b,r,"Memory","kB (1000 bytes), resolution 0.1 kB");
        r=infoRow(b,r,"Picture time","seconds, resolution 0.1 s");
        r=infoRow(b,r,"Signal / battery","dBm / mV");
        for(var s:sheets)r=infoRow(b,r,s.name(),s.entries().size()+" samples ("+s.device()+")");
        return b.append("</sheetData></worksheet>").toString();
    }
    private static int infoRow(StringBuilder b,int r,String a,String c){
        b.append("<row r=\"").append(r).append("\">").append(text("A",r,a,r==1?S_HEADER:0)).append(c.isEmpty()?"":text("B",r,c,0)).append("</row>");
        return r+1;
    }
    private static String sheet(Sheet sheet,ZoneId zone,boolean raw){
        var columns=new ArrayList<>(MetricsCsv.COLUMNS);if(raw)columns.add("raw");
        int width=columns.size();String last=column(width-1);
        var entries=sheet.entries();
        if(entries.size()>MAX_ROWS)entries=entries.subList(entries.size()-MAX_ROWS,entries.size());
        var b=new StringBuilder(XML).append("<worksheet ").append(MAIN).append("><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols>");
        for(int c=0;c<width;c++){
            int w=c<2?20:c==2?24:c==3?18:c==width-1&&raw?40:16;
            b.append("<col min=\"").append(c+1).append("\" max=\"").append(c+1).append("\" width=\"").append(w).append("\" customWidth=\"1\"/>");
        }
        b.append("</cols><sheetData><row r=\"1\">");
        for(int c=0;c<width;c++)b.append(text(column(c),1,columns.get(c),S_HEADER));
        b.append("</row>");
        int r=2;
        for(var e:entries){
            var s=e.sample();var ctx=e.context();
            long second=Math.floorDiv(s.at(),1000L);
            int offset=zone.getRules().getOffset(Instant.ofEpochSecond(second)).getTotalSeconds();
            b.append("<row r=\"").append(r).append("\">");
            b.append(number("A",r,EXCEL_EPOCH_DAYS+second/86400.0,S_TIME)).append(number("B",r,EXCEL_EPOCH_DAYS+(second+offset)/86400.0,S_TIME));
            b.append(text("C",r,sheet.device(),0)).append(text("D",r,ctx.name(),0)).append(text("E",r,ctx.firmware(),0)).append(text("F",r,ctx.reason(),0)).append(text("G",r,s.boot(),0));
            Number[] values={s.up(),tenths(s.freeBytes(),1000),tenths(s.lowestBytes(),1000),tenths(s.blockBytes(),1000),tenths(s.afterTlsBytes(),1000),tenths(s.tlsStackBytes(),1000),tenths(s.pictureMs(),1000),
                    s.rssi(),s.drops(),s.millivolts(),s.brightness(),s.activeSeconds(),s.dimmedSeconds()};
            for(int i=0;i<values.length;i++){
                if(values[i]==null)continue;
                boolean tenth=i>=1&&i<=6;
                b.append(number(column(7+i),r,values[i].doubleValue(),tenth?S_TENTH:0));
            }
            if(raw)b.append(text(column(width-1),r,s.raw(),0));
            b.append("</row>");r++;
        }
        b.append("</sheetData><autoFilter ref=\"A1:").append(last).append(Math.max(1,r-1)).append("\"/></worksheet>");
        return b.toString();
    }
    private static Double tenths(Integer v,int unit){return v==null?null:Math.round(v*10.0/unit)/10.0;}
    private static String column(int index){return index<26?String.valueOf((char)('A'+index)):""+(char)('A'+index/26-1)+(char)('A'+index%26);}
    private static String number(String col,int row,double value,int style){
        String v=value==Math.rint(value)&&Math.abs(value)<1e15?Long.toString((long)value):String.format(Locale.ROOT,"%.10f",value).replaceAll("0+$","");
        return "<c r=\""+col+row+"\""+(style==0?"":" s=\""+style+"\"")+"><v>"+v+"</v></c>";
    }
    private static String text(String col,int row,String value,int style){
        if(value==null||value.isEmpty())return "";
        return "<c r=\""+col+row+"\" t=\"inlineStr\""+(style==0?"":" s=\""+style+"\"")+"><is><t xml:space=\"preserve\">"+escape(value)+"</t></is></c>";
    }
    /** XML text without characters XML 1.0 forbids. */
    static String escape(String value){
        var b=new StringBuilder(value.length()+8);
        value.codePoints().forEach(c->{
            if(c=='&')b.append("&amp;");else if(c=='<')b.append("&lt;");else if(c=='>')b.append("&gt;");else if(c=='"')b.append("&quot;");
            else if(c==9||c==10||c==13||c>=0x20&&c<=0xD7FF||c>=0xE000&&c<=0xFFFD||c>=0x10000&&c<=0x10FFFF)b.appendCodePoint(c);
            else b.append(' ');
        });
        return b.toString();
    }
    /** Sheet names Excel accepts: no []:*?/\, at most 31 characters, not empty, unique ("Info" is taken). */
    static List<String> sheetNames(List<Sheet> sheets){
        var used=new HashSet<String>(List.of("info"));var names=new ArrayList<String>();
        for(var s:sheets){
            String base=(s.name()==null||s.name().isBlank()?s.device():s.name()).replaceAll("[\\[\\]:*?/\\\\\\p{Cntrl}]"," ").strip().replaceAll("^'+|'+$","");
            if(base.isEmpty())base="Display";
            if(base.length()>31)base=base.substring(0,31).strip();
            String name=base;
            for(int i=2;!used.add(name.toLowerCase(Locale.ROOT));i++){String suffix=" ("+i+")";name=base.substring(0,Math.min(base.length(),31-suffix.length()))+suffix;}
            names.add(name);
        }
        return names;
    }
}
