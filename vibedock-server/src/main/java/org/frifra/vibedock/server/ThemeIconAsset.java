package org.frifra.vibedock.server;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Small self-contained SVG subset or bounded PNG. Allows only literal presentation styles; never fonts, references, entities or network access. */
final class ThemeIconAsset {
    static final int MAX_BYTES=128*1024;
    private static final Set<String> ELEMENTS=Set.of("svg","g","defs","path","rect","circle","ellipse","line","polygon","polyline");
    private static final Set<String> ATTRIBUTES=Set.of("xmlns","style","color","viewBox","width","height","fill","fill-rule","clip-rule","stroke",
            "stroke-width","stroke-linecap","stroke-linejoin","stroke-miterlimit","opacity","fill-opacity","stroke-opacity","transform",
            "d","points","x","y","x1","y1","x2","y2","cx","cy","r","rx","ry");
    private static final Set<String> PRESENTATION=Set.of("fill","stroke","color","fill-rule","clip-rule","stroke-width",
            "stroke-linecap","stroke-linejoin","stroke-miterlimit","opacity","fill-opacity","stroke-opacity");
    private ThemeIconAsset() {}
    static BufferedImage draw(String file,byte[] bytes,int size) throws Exception {
        if(bytes.length==0||bytes.length>MAX_BYTES)throw invalid("Theme icons must contain 1 to 128 KiB");
        if(size<8||size>Twemoji.MAX_SIZE)throw invalid();
        var result=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);var g=result.createGraphics();
        try{
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
            if(file.endsWith(".svg")){
                validateSvg(bytes);
                var context=com.github.weisj.jsvg.parser.LoaderContext.builder()
                        .externalResourcePolicy(com.github.weisj.jsvg.parser.resources.ResourcePolicy.DENY_ALL).build();
                var document=new com.github.weisj.jsvg.parser.SVGLoader().load(new ByteArrayInputStream(bytes),null,context);
                if(document==null)throw invalid();
                document.render((java.awt.Component)null,g,new com.github.weisj.jsvg.view.ViewBox(0,0,size,size));
            }else if(file.endsWith(".png")){
                try(var input=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
                    var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw invalid();var reader=readers.next();
                    try{
                        reader.setInput(input);int width=reader.getWidth(0),height=reader.getHeight(0);
                        if(!reader.getFormatName().equalsIgnoreCase("png")||width<1||height<1||width>1024||height>1024)throw invalid();
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g.drawImage(reader.read(0),0,0,size,size,null);
                    }finally{reader.dispose();}
                }
            }else throw invalid();
        }finally{g.dispose();}
        return result;
    }
    private static Document parseSvg(byte[] bytes) throws Exception {
        if(bytes.length==0||bytes.length>MAX_BYTES)throw invalid("Choose an icon of at most 128 KiB");
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var parser=factory.newDocumentBuilder();parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return parser.parse(new ByteArrayInputStream(bytes));
    }
    private static void validateSvg(byte[] bytes) throws Exception {
        var document=parseSvg(bytes);var queue=new ArrayDeque<Node>();queue.add(document);int count=0;
        if(!"svg".equals(document.getDocumentElement().getLocalName()))throw invalid();
        while(!queue.isEmpty()){
            var node=queue.removeFirst();depth(node);if(++count>2048)throw invalid();
            if(node instanceof Element element){
                if(!ELEMENTS.contains(element.getLocalName()))throw invalid("Unsupported SVG element: "+shortName(element.getLocalName()));
                if(!"http://www.w3.org/2000/svg".equals(element.getNamespaceURI()))throw invalid("SVG elements need the SVG namespace");
                if(element!=document.getDocumentElement()&&element.getLocalName().equals("svg"))throw invalid();
                var attributes=element.getAttributes();
                for(int n=0;n<attributes.getLength();n++){
                    var attr=attributes.item(n);String name=attr.getNodeName(),value=attr.getNodeValue();
                    if(!ATTRIBUTES.contains(name))throw invalid("Unsupported SVG attribute: "+shortName(name));
                    if(value.length()>32768)throw invalid("SVG attribute exceeds 32768 characters");
                    attribute(name,value);
                }
            }else if(node.getNodeType()!=Node.DOCUMENT_NODE&&node.getNodeType()!=Node.COMMENT_NODE
                    &&!(node.getNodeType()==Node.TEXT_NODE&&node.getNodeValue().isBlank()))throw invalid();
            var children=node.getChildNodes();for(int n=0;n<children.getLength();n++)queue.addLast(children.item(n));
        }
    }
    record StaticUpload(byte[] svg,int removed) {}
    /** Editor uploads only: discard unsupported content, flatten literal styles, retain safe vector shapes without external resources. */
    static StaticUpload staticSvg(byte[] bytes) throws Exception {
        var document=parseSvg(bytes);var root=document.getDocumentElement();
        if(!"svg".equals(root.getLocalName())||!"http://www.w3.org/2000/svg".equals(root.getNamespaceURI()))throw invalid("Choose an SVG document");
        var queue=new ArrayDeque<Node>();queue.add(root);int count=0,removed=0;
        while(!queue.isEmpty()){
            var node=queue.removeFirst();depth(node);if(++count>2048)throw invalid("SVG exceeds 2048 nodes");
            if(node instanceof Element element){
                if(!ELEMENTS.contains(element.getLocalName())||"defs".equals(element.getLocalName())||
                        !"http://www.w3.org/2000/svg".equals(element.getNamespaceURI())||element!=root&&"svg".equals(element.getLocalName())){
                    node.getParentNode().removeChild(node);removed++;continue;
                }
                var attributes=element.getAttributes();String style=element.getAttribute("style");element.removeAttribute("style");
                for(int n=attributes.getLength()-1;n>=0;n--){
                    var a=attributes.item(n);String name=a.getNodeName();
                    try{
                        if(!ATTRIBUTES.contains(name)||a.getNodeValue().length()>32768)throw invalid();
                        attribute(name,a.getNodeValue());
                    }catch(IllegalArgumentException e){element.removeAttributeNode((Attr)a);removed++;}
                }
                // Inline presentation declarations override matching attributes, as in the original SVG.
                for(String declaration:style.split(";")){
                    if(declaration.isBlank())continue;String[] parts=declaration.split(":",2);
                    try{
                        if(parts.length!=2||!PRESENTATION.contains(parts[0].strip()))throw invalid();
                        String name=parts[0].strip(),value=parts[1].strip();attribute(name,value);element.setAttribute(name,value);
                    }catch(IllegalArgumentException e){removed++;}
                }
            }else if(node.getNodeType()!=Node.TEXT_NODE||!node.getNodeValue().isBlank()){
                node.getParentNode().removeChild(node);removed++;continue;
            }
            var children=node.getChildNodes();for(int n=0;n<children.getLength();n++)queue.add(children.item(n));
        }
        // Transform only this in-memory DOM; external DTDs and stylesheets remain prohibited.
        var factory=javax.xml.transform.TransformerFactory.newInstance();factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
        var output=new ByteArrayOutputStream();factory.newTransformer().transform(new javax.xml.transform.dom.DOMSource(root),new javax.xml.transform.stream.StreamResult(output));
        var image=draw("icon.svg",output.toByteArray(),Twemoji.MAX_SIZE);boolean visible=false;
        for(int y=0;y<image.getHeight()&&!visible;y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)>>>24)!=0){visible=true;break;}
        if(!visible)throw invalid("No visible static shapes remain after SVG cleanup");
        return new StaticUpload(output.toByteArray(),removed);
    }
    record PngInfo(boolean supported,String color) {}
    static PngInfo pngInfo(byte[] bytes) throws Exception {
        draw("icon.png",bytes,Twemoji.SIZE);var image=ImageIO.read(new ByteArrayInputStream(bytes));
        Integer rgb=null;boolean transparent=false,visible=false;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
            int pixel=image.getRGB(x,y),alpha=pixel>>>24;if(alpha<255)transparent=true;if(alpha==0)continue;
            visible=true;int color=pixel&0xffffff;
            if(rgb==null)rgb=color;else if(rgb!=color)return new PngInfo(false,"#000000");
        }
        return new PngInfo(visible&&transparent,String.format("#%06x",rgb==null?0:rgb));
    }
    static byte[] recolorPng(byte[] bytes,String color) throws Exception {
        if(color==null||!color.matches("#[0-9a-fA-F]{6}"))throw invalid("Choose an RGB color");
        if(!pngInfo(bytes).supported())throw invalid("Color override needs a single-color PNG with transparency");
        var source=ImageIO.read(new ByteArrayInputStream(bytes));var image=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_ARGB);int rgb=Integer.parseInt(color.substring(1),16);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,(source.getRGB(x,y)&0xff000000)|rgb);
        var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);
        if(output.size()>MAX_BYTES)throw invalid("Recolored PNG exceeds 128 KiB");return output.toByteArray();
    }

    private static void attribute(String name,String value){
        switch(name){
            case "xmlns" -> {if(!value.equals("http://www.w3.org/2000/svg"))throw invalid();}
            case "style" -> {
                for(String declaration:value.split(";")){
                    if(declaration.isBlank())continue;
                    String[] parts=declaration.split(":",2);
                    if(parts.length!=2||!PRESENTATION.contains(parts[0].strip()))
                        throw invalid("SVG styles may contain only literal fill, stroke, color and opacity properties");
                    attribute(parts[0].strip(),parts[1].strip());
                }
            }
            case "fill","stroke","color" -> {
                if(!value.matches("none|currentColor|#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))
                    throw invalid("SVG colors must be hexadecimal, none or currentColor; references are not supported");
            }
            case "fill-rule","clip-rule" -> {if(!Set.of("nonzero","evenodd").contains(value))throw invalid();}
            case "stroke-linecap" -> {if(!Set.of("butt","round","square").contains(value))throw invalid();}
            case "stroke-linejoin" -> {if(!Set.of("miter","round","bevel").contains(value))throw invalid();}
            case "stroke-width" -> {
                if(!value.matches("(?:[0-9]*\\.[0-9]+|[0-9]+(?:\\.[0-9]*)?)(?:px)?"))throw invalid("SVG stroke width must be a non-negative number, optionally in px");
                numbers(value.replace("px",""));
            }
            case "d" -> {
                if(!value.matches("[MmLlHhVvCcSsQqTtAaZz0-9eE.,+\\-\\s]*"))throw invalid();
                numbers(value.replaceAll("[MmLlHhVvCcSsQqTtAaZz]"," "));
            }
            case "transform" -> {
                if(!value.matches("(?:\\s*(?:matrix|translate|scale|rotate|skewX|skewY)\\s*\\([-+0-9eE.,\\s]+\\)\\s*)+"))throw invalid();
                numbers(value.replaceAll("[A-Za-z]+\\("," ").replace(')',' '));
            }
            default -> numbers(value);
        }
    }
    private static void depth(Node node){
        int levels=0;for(Node current=node;current!=null;current=current.getParentNode())
            if(++levels>64)throw invalid("SVG nesting exceeds 64 levels");
    }
    private static String shortName(String value){return value.substring(0,Math.min(80,value.length()));}
    private static void numbers(String value){
        for(String part:value.strip().split("[\\s,]+")){
            // SVG paths can put a sign directly after the previous number, e.g. 4-2.
            var matcher=java.util.regex.Pattern.compile("[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?").matcher(part);int end=0;
            while(matcher.find()){
                if(matcher.start()!=end)throw invalid();double n=Double.parseDouble(matcher.group());if(!Double.isFinite(n)||Math.abs(n)>16384)throw invalid();end=matcher.end();
            }
            if(end!=part.length())throw invalid();
        }
    }
    private static IllegalArgumentException invalid(){return invalid("Unsupported or oversized theme icon");}
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException(reason);}
}
