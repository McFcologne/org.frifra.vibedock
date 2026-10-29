package org.frifra.vibedock.server;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Small self-contained SVG subset or bounded PNG. Never allows fonts, CSS, references, entities or network access. */
final class ThemeIconAsset {
    static final int MAX_BYTES=128*1024;
    private static final Set<String> ELEMENTS=Set.of("svg","g","path","rect","circle","ellipse","line","polygon","polyline");
    private static final Set<String> ATTRIBUTES=Set.of("xmlns","viewBox","width","height","fill","fill-rule","clip-rule","stroke",
            "stroke-width","stroke-linecap","stroke-linejoin","stroke-miterlimit","opacity","fill-opacity","stroke-opacity","transform",
            "d","points","x","y","x1","y1","x2","y2","cx","cy","r","rx","ry");
    private ThemeIconAsset() {}
    static BufferedImage draw(String file,byte[] bytes,int size) throws Exception {
        if(bytes.length==0||bytes.length>MAX_BYTES||size<8||size>Twemoji.MAX_SIZE)throw invalid();
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
    private static void validateSvg(byte[] bytes) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var parser=factory.newDocumentBuilder();parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        var document=parser.parse(new ByteArrayInputStream(bytes));var queue=new ArrayDeque<Node>();queue.add(document);int count=0;
        if(!"svg".equals(document.getDocumentElement().getLocalName()))throw invalid();
        while(!queue.isEmpty()){
            var node=queue.removeFirst();if(++count>2048)throw invalid();
            if(node instanceof Element element){
                if(!ELEMENTS.contains(element.getLocalName())||!"http://www.w3.org/2000/svg".equals(element.getNamespaceURI()))throw invalid();
                if(element!=document.getDocumentElement()&&element.getLocalName().equals("svg"))throw invalid();
                var attributes=element.getAttributes();
                for(int n=0;n<attributes.getLength();n++){
                    var attr=attributes.item(n);String name=attr.getNodeName(),value=attr.getNodeValue();
                    if(!ATTRIBUTES.contains(name)||value.length()>32768)throw invalid();
                    switch(name){
                        case "xmlns" -> {if(!value.equals("http://www.w3.org/2000/svg"))throw invalid();}
                        case "fill","stroke" -> {if(!value.matches("none|#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))throw invalid();}
                        case "fill-rule","clip-rule" -> {if(!Set.of("nonzero","evenodd").contains(value))throw invalid();}
                        case "stroke-linecap" -> {if(!Set.of("butt","round","square").contains(value))throw invalid();}
                        case "stroke-linejoin" -> {if(!Set.of("miter","round","bevel").contains(value))throw invalid();}
                        case "d" -> {if(!value.matches("[MmLlHhVvCcSsQqTtAaZz0-9eE.,+\\-\\s]*"))throw invalid();numbers(value.replaceAll("[MmLlHhVvCcSsQqTtAaZz]"," "));}
                        case "transform" -> {
                            if(!value.matches("(?:\\s*(?:matrix|translate|scale|rotate|skewX|skewY)\\s*\\([-+0-9eE.,\\s]+\\)\\s*)+"))throw invalid();
                            numbers(value.replaceAll("[A-Za-z]+\\("," ").replace(')',' '));
                        }
                        default -> numbers(value);
                    }
                }
            }else if(node.getNodeType()!=Node.DOCUMENT_NODE&&node.getNodeType()!=Node.COMMENT_NODE
                    &&!(node.getNodeType()==Node.TEXT_NODE&&node.getNodeValue().isBlank()))throw invalid();
            var children=node.getChildNodes();for(int n=0;n<children.getLength();n++)queue.addLast(children.item(n));
        }
    }
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
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Unsupported or oversized theme icon");}
}
