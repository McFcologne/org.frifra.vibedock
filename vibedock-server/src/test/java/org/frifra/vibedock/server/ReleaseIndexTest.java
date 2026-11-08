package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReleaseIndexTest {
    static final String HASH="a".repeat(64);
    static String file(String name){return "{\"file\":\""+name+"\",\"sha256\":\""+HASH+"\",\"size\":2048}";}
    static String index(String id,String extra){
        return "{\"schema\":1,\"client\":\"1.2.0\",\"protocol\":2,\"boards\":[{\"id\":\""+id+"\",\"flash\":"+file("f.bin")+",\"app\":"+file("a.bin")+",\"manifest\":"+file("m.json")+extra+"}]}";
    }
    private static String error(String json){return assertThrows(IllegalArgumentException.class,()->ReleaseIndex.parse(json.getBytes())).getMessage();}

    @Test void readsTheIndexOfARelease(){
        var index=ReleaseIndex.parse(index("esp32-cyd","").getBytes());
        assertEquals("1.2.0",index.client());assertEquals(2,index.protocol());
        var board=index.board("esp32-cyd").orElseThrow();
        assertEquals("a.bin",board.app().file());assertEquals(2048,board.flash().size());assertTrue(index.board("other").isEmpty());
    }
    @Test void refusesWhatIsNotAsExpected(){
        assertTrue(error("{}").contains("schema"));
        assertTrue(error(index("esp32-cyd","").replace("\"schema\":1","\"schema\":2")).contains("schema"));
        assertTrue(error(index("../x","")).contains("id"));
        assertTrue(error(index("esp32-cyd","").replace("a.bin","../a.bin")).contains("file"));
        assertTrue(error(index("esp32-cyd","").replace(HASH,"XYZ")).contains("sha256"));
        assertTrue(error(index("esp32-cyd","").replace("2048","99999999")).contains("size"));
        assertTrue(error(index("esp32-cyd","").replace("\"flash\":"+file("f.bin")+",","")).contains("flash"));
        assertTrue(error("not json").contains("JSON"));
        assertTrue(error("{\"schema\":1,\"client\":\"1\",\"protocol\":2,\"boards\":[]}").contains("boards"));
    }
}
