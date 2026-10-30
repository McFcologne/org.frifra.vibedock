package org.frifra.vibedock.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** An offline asset sheet with an actual renderer snapshot. No scripts, external links or arbitrary styles. */
final class ThemePreviewPage {
    static final String FILE="preview.html";
    private static final String CSP="default-src 'none'; img-src 'self' file:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'";
    private static final String CSS="body{font:16px system-ui,sans-serif;background:#f3f5f9;color:#16202f;margin:24px}main{max-width:960px;margin:auto}section{display:flex;gap:16px;flex-wrap:wrap}figure{background:#fff;border:1px solid #bbb;border-radius:12px;padding:16px;margin:0 0 16px}img{max-width:100%;height:auto}figure.icon img{width:80px;height:80px;background:repeating-conic-gradient(#687385 0% 25%,#a5adba 0% 50%) 50%/12px 12px;padding:8px}figcaption{margin-top:12px}code{overflow-wrap:anywhere}p{line-height:1.5}";
    private static final byte[] LOGO_BYTES=loadLogo();
    static final String LOGO="preview/vibedock-"+ThemePackage.sha256(LOGO_BYTES)+".png";
    private static final String BRAND_CSS="p.brand{display:flex;align-items:center;gap:10px;font-weight:700;font-size:22px}p.brand img{width:40px;height:40px}";
    private ThemePreviewPage() {}
    private static byte[] loadLogo(){
        try(var in=ThemePreviewPage.class.getResourceAsStream("/static/apple-touch-icon.png")){
            if(in==null)throw new IOException("Missing VibeDock icon");return in.readNBytes(128*1024);
        }catch(IOException e){throw new java.io.UncheckedIOException(e);}
    }
    static byte[] logo(){return LOGO_BYTES.clone();}
    static byte[] page(ThemePackage.Manifest m){
        var out=new StringBuilder("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"utf-8\"/><meta http-equiv=\"Content-Security-Policy\" content=\"")
                .append(escape(CSP)).append("\"/><title>").append(escape(m.name())).append(" · VibeDock theme preview</title><link rel=\"icon\" type=\"image/png\" href=\"").append(LOGO).append("\"/><style>").append(CSS).append("</style><style>").append(BRAND_CSS).append("</style></head><body><main><p class=\"brand\"><img src=\"").append(LOGO).append("\" alt=\"VibeDock\"/>VibeDock</p><h1>")
                .append(escape(m.name())).append("</h1><p><code>").append(escape(m.id())).append("</code> · ").append(escape(m.version())).append("</p>");
        if(m.preview()!=null)out.append("<h2>Display preview</h2><figure><img src=\"").append(escape(m.preview())).append("\" alt=\"Theme rendered with sample readings\"/><figcaption>Actual VibeDock renderer · sample readings</figcaption></figure>");
        out.append("<h2>Included icons</h2><section>");
        for(var icon:m.icons())out.append("<figure class=\"icon\"><img src=\"").append(escape(icon.file())).append("\" alt=\"").append(escape(icon.label())).append("\"/><figcaption>")
                .append(escape(icon.label())).append(icon.id().equals(m.defaultIcon())?" · default":" · catalog alternative").append("<p>Creator: ").append(escape(icon.author()==null?"Not specified":icon.author())).append("</p><p>License: ").append(escape(icon.license()==null?"Not specified":icon.license())).append("</p><p><code>").append(escape(icon.file())).append("</code></p></figcaption></figure>");
        out.append("</section><p>Author: ").append(escape(m.author())).append("</p><p>License: ").append(escape(m.license())).append(". See the included license notices.</p></main></body></html>");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    static void validate(byte[] bytes,ThemePackage.Manifest manifest) throws IOException {
        if(bytes.length==0||bytes.length>128*1024)throw new IllegalArgumentException("Theme preview HTML exceeds 128 KiB");
        try{
            var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setXIncludeAware(false);f.setExpandEntityReferences(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var parser=f.newDocumentBuilder();parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());var doc=parser.parse(new ByteArrayInputStream(bytes));
            var allowed=Set.of("html","head","meta","title","style","body","main","h1","h2","p","section","figure","figcaption","img","code","link");
            var queue=new ArrayDeque<Node>();queue.add(doc);int count=0;boolean policy=false;
            if(!"html".equals(doc.getDocumentElement().getLocalName()))throw new IllegalArgumentException();
            while(!queue.isEmpty()){
                var node=queue.removeFirst();if(++count>512)throw new IllegalArgumentException();
                int depth=0;for(Node n=node;n!=null;n=n.getParentNode())if(++depth>32)throw new IllegalArgumentException();
                if(node instanceof Element e){
                    String tag=e.getLocalName();if(!allowed.contains(tag)||!"http://www.w3.org/1999/xhtml".equals(e.getNamespaceURI()))throw new IllegalArgumentException();
                    var attrs=e.getAttributes();
                    for(int n=0;n<attrs.getLength();n++){
                        var a=attrs.item(n);String name=a.getNodeName(),value=a.getNodeValue();
                        boolean valid=switch(name){
                            case "xmlns" -> tag.equals("html")&&value.equals("http://www.w3.org/1999/xhtml");
                            case "class" -> tag.equals("figure")&&value.equals("icon")||tag.equals("p")&&value.equals("brand");
                            case "charset" -> tag.equals("meta")&&value.equals("utf-8");
                            case "http-equiv" -> tag.equals("meta")&&value.equals("Content-Security-Policy");
                            case "content" -> tag.equals("meta")&&value.equals(CSP);
                            case "src" -> tag.equals("img")&&manifest.files().containsKey(value)&&(value.endsWith(".png")||value.endsWith(".svg"));
                            case "rel" -> tag.equals("link")&&value.equals("icon");
                            case "type" -> tag.equals("link")&&value.equals("image/png");
                            case "href" -> tag.equals("link")&&manifest.files().containsKey(value)&&value.endsWith(".png");
                            case "alt" -> tag.equals("img")&&value.length()<=160;
                            default -> false;
                        };if(!valid)throw new IllegalArgumentException();
                    }
                    if(tag.equals("style")&&(!(e.getTextContent().equals(CSS)||e.getTextContent().equals(BRAND_CSS))||e.getChildNodes().getLength()!=1))throw new IllegalArgumentException();
                    if(tag.equals("meta")&&e.hasAttribute("http-equiv")){
                        if(!CSP.equals(e.getAttribute("content")))throw new IllegalArgumentException();policy=true;
                    }
                }else if(node.getNodeType()!=Node.DOCUMENT_NODE&&node.getNodeType()!=Node.TEXT_NODE)throw new IllegalArgumentException();
                var children=node.getChildNodes();for(int n=0;n<children.getLength();n++)queue.add(children.item(n));
            }
            if(!policy)throw new IllegalArgumentException();
        }catch(Exception e){throw new IllegalArgumentException("Theme preview HTML must be a static VibeDock preview without scripts or external resources",e);}
    }
}
