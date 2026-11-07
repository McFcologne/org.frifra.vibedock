package org.frifra.vibedock.server;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The request for a firmware of a new board: what the form on the firmware page collects, as a JSON report. Little is asked
 * on purpose: the product page, where it was bought, what the board reported over USB (chip, flash size, features) and a
 * photo. Nobody builds from it automatically: a person goes through the manufacturer's pages with it, builds the firmware
 * and sends back the package (firmware.bin, flash.bin, manifest.json, version "<current>-dirty") that the administration
 * uploads. Everything is checked strictly; blank answers are left out.
 */
final class FirmwareRequest {
    private FirmwareRequest() {}
    static final int MAX_FIELD=1000;
    private static final Pattern ID=Pattern.compile("[a-z0-9-]{1,40}"),URL=Pattern.compile("https?://[^\\s<>\"'\\\\]{4,300}");

    /** The report for the answers of the form (name → text); IllegalArgumentException names the first wrong answer. */
    static Map<String,Object> report(Map<String,String> form,String serverVersion,Instant now){return report(form,serverVersion,now,false);}
    /** With photo: the package carries a photo of the board (photo.jpg) beside the report. */
    static Map<String,Object> report(Map<String,String> form,String serverVersion,Instant now,boolean photo){
        var f=new Answers(form);
        var report=new LinkedHashMap<String,Object>();
        report.put("schema",2);report.put("kind","firmware-request");report.put("createdAt",now.toString());
        report.put("serverVersion",serverVersion==null?"unknown":serverVersion);

        String label=f.text("board.label",true,80);
        if(label.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("The board name must be plain text");
        String id=f.pattern("board.id",ID,"lowercase letters, digits and dashes, up to 40");
        if(id.isEmpty())id=slug(label);
        var board=new LinkedHashMap<String,Object>();
        board.put("id",id);board.put("label",label);
        f.put(board,"chip",f.choice("board.chip",Manifest.CHIPS,false));
        f.put(board,"flashMb",f.integer("board.flashMb",1,64));
        f.put(board,"usbReport",f.text("board.usbReport",false,MAX_FIELD));
        report.put("board",board);

        var maker=new LinkedHashMap<String,Object>();
        String product=f.url("maker.productUrl");
        if(product.isEmpty())throw new IllegalArgumentException("maker.productUrl is missing");
        maker.put("productUrl",product);f.put(maker,"shopUrl",f.url("maker.shopUrl"));
        report.put("maker",maker);
        if(photo)report.put("photo","photo.jpg");
        f.put(report,"notes",f.text("notes",false,MAX_FIELD));
        f.put(report,"contact",f.text("contact",false,200));
        // How the answer comes back.
        report.put("delivery",Map.of("files",List.of("firmware.bin","flash.bin","manifest.json"),"version","<server version>-dirty"));
        return report;
    }
    /** The package that goes back to the person asking: request.json and, when given, photo.jpg. */
    static byte[] pack(Map<String,Object> report,byte[] photo) throws java.io.IOException {
        var out=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(out)){
            zip.putNextEntry(new java.util.zip.ZipEntry("request.json"));
            zip.write(JsonCompatibility.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(report));zip.closeEntry();
            if(photo!=null){zip.putNextEntry(new java.util.zip.ZipEntry("photo.jpg"));zip.write(photo);zip.closeEntry();}
        }
        return out.toByteArray();
    }
    /** A board id from a name: lowercase letters and digits, single dashes, at most 40 characters. */
    static String slug(String label){
        String s=label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-+|-+$","");
        if(s.length()>40)s=s.substring(0,40).replaceAll("-+$","");
        if(s.isEmpty())throw new IllegalArgumentException("The board name needs letters or digits");
        return s;
    }

    /** The answers of the form with the checks of one kind each. */
    private static final class Answers {
        private final Map<String,String> form;
        Answers(Map<String,String> form){this.form=form==null?Map.of():form;}
        private String raw(String name){var v=form.get(name);return v==null?"":v.trim();}
        String text(String name,boolean required,int max){
            String v=raw(name);
            if(v.isEmpty()){if(required)throw new IllegalArgumentException(name+" is missing");return "";}
            if(v.length()>max)throw new IllegalArgumentException(name+" is longer than "+max+" characters");
            if(v.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw new IllegalArgumentException(name+" must be plain text");
            return v;
        }
        String pattern(String name,Pattern pattern,String shape){
            String v=raw(name);
            if(v.isEmpty())return "";
            if(!pattern.matcher(v).matches())throw new IllegalArgumentException(name+" must be "+shape);
            return v;
        }
        String choice(String name,Set<String> options,boolean required){
            String v=raw(name);
            if(v.isEmpty()){if(required)throw new IllegalArgumentException(name+" is missing");return "";}
            if(!options.contains(v))throw new IllegalArgumentException(name+" must be one of "+new TreeSet<>(options));
            return v;
        }
        String url(String name){
            String v=raw(name);
            if(v.isEmpty())return "";
            if(!URL.matcher(v).matches())throw new IllegalArgumentException(name+" must be a web address (http or https)");
            return v;
        }
        Integer integer(String name,int min,int max){
            boolean required=false;
            String v=raw(name);
            if(v.isEmpty()){if(required)throw new IllegalArgumentException(name+" is missing");return null;}
            int n;
            try{n=Integer.parseInt(v);}catch(NumberFormatException e){throw new IllegalArgumentException(name+" is not a whole number");}
            if(n<min||n>max)throw new IllegalArgumentException(name+" must be from "+min+" to "+max);
            return n;
        }
        void put(Map<String,Object> into,String key,Object value){
            if(value==null||value instanceof String s&&s.isEmpty())return;
            into.put(key,value);
        }
        void putLong(Map<String,Object> into,String key,String value){put(into,key,value);}
    }
}
