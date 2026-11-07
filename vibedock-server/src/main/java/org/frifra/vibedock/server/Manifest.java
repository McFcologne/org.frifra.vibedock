package org.frifra.vibedock.server;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The manifest of one firmware build (manifest.json next to firmware.bin and flash.bin): which board the build is, what
 * its display and capabilities are, how its case looks. Built by the firmware build, read by the server only, never part
 * of the device image. {@link #parse} checks everything strictly; what it returns can be used without further checks.
 * <ul>
 * <li>checksum: SHA-256 of the manifest without this field in canonical form ({@link #canonical}).</li>
 * <li>firmware.sha256 / flashSha256: SHA-256 of firmware.bin / flash.bin; {@link #verifyBinaries} compares them.</li>
 * </ul>
 */
public record Manifest(int schema,String id,String label,String chip,String version,String hardware,Binaries firmware,
                       Screen display,Capabilities capabilities,Case boardCase) {
    public static final int SCHEMA=1,MAX_BYTES=16*1024,DISPLAY_MIN=64,DISPLAY_MAX=480;
    public static final Set<String> CHIPS=Set.of("ESP8266","ESP32","ESP32-S2","ESP32-S3","ESP32-C3","ESP32-C6","ESP32-H2");
    private static final Pattern ID=Pattern.compile("[a-z0-9-]{1,40}"),VERSION=Pattern.compile("[0-9A-Za-z.+-]{1,40}"),
            HEX=Pattern.compile("[0-9a-f]{64}"),COLOUR=Pattern.compile("#[0-9a-fA-F]{6}");
    private static final tools.jackson.databind.ObjectMapper JSON=JsonCompatibility.mapper();

    /** sha256 of firmware.bin; flashSha256 of flash.bin (null: the build has none). */
    public record Binaries(String sha256,String flashSha256) {}
    /** width, height: pixels; corner: radius of rounded panel corners in pixels; grey: black-and-white panel. */
    public record Screen(int width,int height,int corner,boolean grey) {}
    /** rotations: clockwise degrees the administration offers, always with 0. */
    public record Capabilities(boolean touch,boolean battery,List<Integer> rotations,boolean powerSaving) {}
    /** margin: top, right, bottom, left, without the rim; all measures are multiples of the screen width. */
    public record Case(List<BigDecimal> margin,BigDecimal rim,BigDecimal radius,String colour) {}

    /** The manifest in the bytes of manifest.json; throws IllegalArgumentException naming what is wrong. */
    public static Manifest parse(byte[] bytes){
        if(bytes==null||bytes.length==0)throw new IllegalArgumentException("The manifest is empty");
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("The manifest is larger than "+MAX_BYTES/1024+" KB");
        JsonNode root;
        try{root=JSON.readTree(bytes);}catch(RuntimeException e){throw new IllegalArgumentException("The manifest is not valid JSON");}
        if(root==null||!root.isObject())throw new IllegalArgumentException("The manifest is not a JSON object");
        int schema=integer(root,"schema",SCHEMA,SCHEMA,SCHEMA);
        String checksum=text(root,"checksum",true,HEX,"a SHA-256 in hexadecimal");
        if(!sha256(canonical(root)).equals(checksum))throw new IllegalArgumentException("The checksum of the manifest does not match its content");
        String id=text(root,"id",true,ID,"lowercase letters, digits and dashes, up to 40"),
                version=text(root,"version",true,VERSION,"letters, digits and . + -, up to 40"),
                chip=text(root,"chip",true,null,null),label=text(root,"label",true,null,null),
                hardware=root.has("hardware")?text(root,"hardware",true,null,null):"prepared";
        if(!CHIPS.contains(chip))throw new IllegalArgumentException("Unknown chip "+shorten(chip));
        if(label.length()>80||label.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("The label must be plain text up to 80 characters");
        if(!hardware.equals("tested")&&!hardware.equals("prepared"))throw new IllegalArgumentException("hardware is tested or prepared");
        var fw=object(root,"firmware",true);
        var binaries=new Binaries(text(fw,"sha256",true,HEX,"a SHA-256 in hexadecimal"),text(fw,"flashSha256",false,HEX,"a SHA-256 in hexadecimal"));
        var d=object(root,"display",true);
        var screen=new Screen(integer(d,"width",null,DISPLAY_MIN,DISPLAY_MAX),integer(d,"height",null,DISPLAY_MIN,DISPLAY_MAX),
                integer(d,"corner",0,0,100),bool(d,"grey"));
        var c=root.has("capabilities")?object(root,"capabilities",true):null;
        var capabilities=new Capabilities(c!=null&&bool(c,"touch"),c!=null&&bool(c,"battery"),c==null?List.of(0):rotations(c),c!=null&&bool(c,"powerSaving"));
        Case boardCase=root.has("case")?boardCase(object(root,"case",true)):null;
        return new Manifest(schema,id,label,chip,version,hardware,binaries,screen,capabilities,boardCase);
    }
    private static Case boardCase(JsonNode n){
        if(!"generic".equals(text(n,"kind",true,null,null)))throw new IllegalArgumentException("case.kind is generic");
        var margin=new ArrayList<BigDecimal>();
        var m=n.get("margin");
        if(m==null||!m.isArray()||m.size()!=4)throw new IllegalArgumentException("case.margin has four numbers: top, right, bottom, left");
        for(var v:m)margin.add(number(v,"case.margin",0,2));
        String colour=n.has("colour")?text(n,"colour",true,COLOUR,"#rrggbb"):"#eeeeea";
        return new Case(List.copyOf(margin),number(n.get("rim"),"case.rim",0,"0.3"),number(n.get("radius"),"case.radius",0,"0.5"),colour);
    }
    private static List<Integer> rotations(JsonNode c){
        var n=c.get("rotations");
        if(n==null)return List.of(0);
        if(!n.isArray()||n.size()>4)throw new IllegalArgumentException("capabilities.rotations is a list of 0, 90, 180, 270");
        var out=new TreeSet<Integer>();out.add(0);
        for(var v:n){
            if(!v.isIntegralNumber()||!Set.of(0,90,180,270).contains(v.asInt(-1)))throw new IllegalArgumentException("capabilities.rotations is a list of 0, 90, 180, 270");
            out.add(v.asInt());
        }
        return List.copyOf(out);
    }
    private static JsonNode object(JsonNode parent,String name,boolean required){
        var n=parent.get(name);
        if(n==null||n.isNull()){if(required)throw new IllegalArgumentException(name+" is missing");return null;}
        if(!n.isObject())throw new IllegalArgumentException(name+" is not an object");
        return n;
    }
    private static String text(JsonNode parent,String name,boolean required,Pattern pattern,String shape){
        var n=parent.get(name);
        if(n==null||n.isNull()){if(required)throw new IllegalArgumentException(name+" is missing");return null;}
        if(!n.isString())throw new IllegalArgumentException(name+" is not a text");
        String v=n.asString();
        if(v.isEmpty())throw new IllegalArgumentException(name+" is empty");
        if(pattern!=null&&!pattern.matcher(v).matches())throw new IllegalArgumentException(name+" must be "+shape);
        return v;
    }
    private static int integer(JsonNode parent,String name,Integer fallback,int min,int max){
        var n=parent.get(name);
        if(n==null||n.isNull()){if(fallback!=null)return fallback;throw new IllegalArgumentException(name+" is missing");}
        if(!n.isIntegralNumber())throw new IllegalArgumentException(name+" is not a whole number");
        int v=n.asInt(Integer.MIN_VALUE);
        if(v<min||v>max)throw new IllegalArgumentException(name+" must be from "+min+" to "+max);
        return v;
    }
    private static boolean bool(JsonNode parent,String name){
        var n=parent.get(name);
        if(n==null||n.isNull())return false;
        if(!n.isBoolean())throw new IllegalArgumentException(name+" is not true or false");
        return n.asBoolean();
    }
    private static BigDecimal number(JsonNode n,String name,double min,String max){return number(n,name,min,Double.parseDouble(max));}
    private static BigDecimal number(JsonNode n,String name,double min,double max){
        if(n==null||!n.isNumber())throw new IllegalArgumentException(name+" is not a number");
        BigDecimal v=n.decimalValue();
        if(v.doubleValue()<min||v.doubleValue()>max)throw new IllegalArgumentException(name+" must be from "+min+" to "+max);
        return v.stripTrailingZeros();
    }
    private static String shorten(String s){return s.length()>20?s.substring(0,20)+"…":s;}

    /** The build's firmware.bin (and flash.bin, when the manifest names one) are the ones the manifest was made for. */
    public void verifyBinaries(byte[] firmwareBin,byte[] flashBin){
        if(!sha256(firmwareBin).equals(firmware.sha256()))throw new IllegalArgumentException("firmware.bin is not the file this manifest was made for");
        if(firmware.flashSha256()!=null&&(flashBin==null||!sha256(flashBin).equals(firmware.flashSha256())))
            throw new IllegalArgumentException("flash.bin is not the file this manifest was made for");
    }
    /** The identity the firmware carries (see {@link Firmware#identity}) names this build: same board id and version. */
    public void verifyIdentity(String board,String version){
        if(!id.equals(board))throw new IllegalArgumentException("The firmware is for the board "+board+", the manifest for "+id);
        if(!this.version.equals(version))throw new IllegalArgumentException("The firmware is version "+version+", the manifest "+this.version);
    }

    /**
     * Canonical text of a manifest without its checksum: compact JSON, keys sorted, numbers without trailing zeros.
     * The build script writes the same text before it takes the SHA-256.
     */
    public static String canonical(JsonNode root){
        var out=new StringBuilder();write(root,out,true);return out.toString();
    }
    private static void write(JsonNode n,StringBuilder out,boolean top){
        if(n.isObject()){
            var names=new TreeSet<String>(n.propertyNames());
            if(top)names.remove("checksum");
            out.append('{');boolean first=true;
            for(String name:names){
                if(!first)out.append(',');first=false;
                string(name,out);out.append(':');write(n.get(name),out,false);
            }
            out.append('}');
        }else if(n.isArray()){
            out.append('[');boolean first=true;
            for(var v:n){if(!first)out.append(',');first=false;write(v,out,false);}
            out.append(']');
        }else if(n.isString())string(n.asString(),out);
        else if(n.isNumber()){var d=n.decimalValue().stripTrailingZeros();out.append(d.signum()==0?"0":d.toPlainString());}
        else if(n.isBoolean())out.append(n.asBoolean());
        else out.append("null");
    }
    private static void string(String s,StringBuilder out){
        out.append('"');
        for(char ch:s.toCharArray()){
            switch(ch){
                case '"'->out.append("\\\"");
                case '\\'->out.append("\\\\");
                default->{if(ch<0x20)out.append(String.format("\\u%04x",(int)ch));else out.append(ch);}
            }
        }
        out.append('"');
    }
    /** The SHA-256 of a text (UTF-8) or of bytes, lowercase hexadecimal. */
    public static String sha256(String text){return sha256(text.getBytes(StandardCharsets.UTF_8));}
    public static String sha256(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    /** The checksum a manifest with this content has (for the build script's counterpart and for tests). */
    public static String checksumOf(String json){return sha256(canonical(JSON.readTree(json)));}
}
