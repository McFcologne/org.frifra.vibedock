package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerLogControllerTest {
    @Test void returnsBoundedRecentUtf8LinesWithoutCaching(@TempDir Path dir) throws Exception {
        String old="old entry\n".repeat(40_000),recent="current TLS error äöü\n".repeat(500);
        Files.writeString(dir.resolve("vibedock.log"),old+recent);
        var response=new ServerLogController(dir.toString()).read();
        assertEquals(200,response.getStatusCode().value());assertEquals("no-store",response.getHeaders().getCacheControl());
        assertEquals("text/plain;charset=UTF-8",response.getHeaders().getContentType().toString());
        assertEquals(500,response.getBody().lines().count());assertFalse(response.getBody().contains("old entry"));
        assertTrue(response.getBody().contains("äöü"));
    }
    @Test void missingLogAndSymlinksAreHandledWithoutReadingOtherFiles(@TempDir Path dir) throws Exception {
        var controller=new ServerLogController(dir.toString());
        assertEquals("No server log available yet.",controller.read().getBody());
        Path other=dir.resolve("private.txt");Files.writeString(other,"must not be read");
        Files.createSymbolicLink(dir.resolve("vibedock.log"),other);
        var rejected=controller.read();assertEquals(503,rejected.getStatusCode().value());
        assertFalse(rejected.getBody().contains("must not be read"));
    }
}
