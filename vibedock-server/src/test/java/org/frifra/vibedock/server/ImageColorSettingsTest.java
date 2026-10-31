package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageColorSettingsTest {
    @TempDir Path dir;
    @Test void damagedOrInvalidFilesFallBackToTheDefaultRules() throws Exception {
        for(String content:new String[]{"{\"rules\":[{\"maxWidth\":240,\"maxHeight\":240,\"colors\":16}]}","{\"rules\":","not json","[]","null",""}){
            Files.writeString(dir.resolve("image-colors.json"),content);
            var settings=new ImageColorSettings(dir.toString(),new JsonMapper());
            assertEquals(ImageColorSettings.DEFAULT,settings.get(),content);
        }
    }
    @Test void aValidFileIsUsedAndASavedPolicyIsReadBack() throws Exception {
        var json=new JsonMapper();var settings=new ImageColorSettings(dir.toString(),json);
        assertEquals(ImageColorSettings.DEFAULT,settings.get());
        var custom=new ImageColorSettings.Mapping(java.util.List.of(new ImageColorSettings.Rule(240,240,64)),false);
        settings.save(custom);
        assertEquals(custom,new ImageColorSettings(dir.toString(),json).get());
    }
}
