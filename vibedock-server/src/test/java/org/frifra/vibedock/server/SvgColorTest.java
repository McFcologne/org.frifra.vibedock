package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SvgColorTest {
    private static byte[] svg(String body){return ("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\">"+body+"</svg>").getBytes(StandardCharsets.UTF_8);}
    @Test void uniformVectorColorPreservesHolesGeometryAndAllOpacitySources() throws Exception {
        byte[] source=svg("<g fill=\"none\" color=\"#11223380\" opacity=\"0.6\"><rect x=\"4\" y=\"4\" width=\"18\" height=\"18\" stroke=\"currentColor\" stroke-width=\"3\"/>"
                +"<rect x=\"28\" y=\"4\" width=\"16\" height=\"16\" fill=\"#abcf\" fill-opacity=\"0.5\"/>"
                +"<rect x=\"4\" y=\"28\" width=\"16\" height=\"16\" fill=\"#abcdef40\"/>"
                +"<rect x=\"28\" y=\"28\" width=\"16\" height=\"16\" style=\"fill:#ee0044;fill-opacity:0.7\"/></g>");
        var original=ThemeIconAsset.draw("icon.svg",source,48);
        byte[] colored=ThemeIconAsset.recolorSvg(source,"#00ff00");var actual=ThemeIconAsset.draw("icon.svg",colored,48);
        assertTrue(new String(colored,StandardCharsets.UTF_8).contains("<svg"));
        assertEquals(0,actual.getRGB(12,12)>>>24,"unfilled rectangle stays hollow");
        for(int y=0;y<48;y++)for(int x=0;x<48;x++){
            int before=original.getRGB(x,y)>>>24,after=actual.getRGB(x,y)>>>24;
            assertEquals(before,after,"alpha / geometry at "+x+","+y);
            if(after>0)assertEquals(0x00ff00,actual.getRGB(x,y)&0xffffff,"paint at "+x+","+y);
        }
    }
    @Test void implicitFillAndNestedCurrentColorAreExplicitAndRemainSafe() throws Exception {
        byte[] source=svg("<rect width=\"12\" height=\"12\"/><g color=\"#ff0000\"><g color=\"currentColor\"><rect x=\"20\" width=\"20\" height=\"20\" fill=\"currentColor\"/></g></g>");
        byte[] colored=ThemeIconAsset.recolorSvg(source,"#1267ab");var actual=ThemeIconAsset.draw("icon.svg",colored,48);
        assertEquals(0xff1267ab,actual.getRGB(4,4));assertEquals(0xff1267ab,actual.getRGB(24,4));
        assertEquals("#1267ab",ThemeIconAsset.svgInfo(colored).color());
        assertTrue(ThemeIconAsset.svgInfo(colored).supported());
        for(String value:List.of("red","url(https://example.com/x)","#badhex","#11223344"))
            assertThrows(IllegalArgumentException.class,()->ThemeIconAsset.recolorSvg(source,value));
        assertThrows(Exception.class,()->ThemeIconAsset.recolorSvg("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/hosts'>]><svg xmlns=\"http://www.w3.org/2000/svg\">&x;</svg>".getBytes(),"#112233"));
    }
}
