package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationInfoTest {
    @Test void versionComesFromTheBuildOrTheGitTag(){
        assertEquals("0.2.0",ApplicationInfo.version("0.2.0","v0.1.0-5-gabc1234"));
        assertEquals("0.1.0-5-gabc1234-dirty",ApplicationInfo.version("0.0.0-SNAPSHOT","v0.1.0-5-gabc1234-dirty"));
        assertEquals("0.2.0",ApplicationInfo.version("0.0.0-SNAPSHOT","0.2.0"));
        assertEquals("0.0.0-gabc1234",ApplicationInfo.version("0.0.0-SNAPSHOT","abc1234"));
        assertEquals("0.0.0-SNAPSHOT",ApplicationInfo.version("0.0.0-SNAPSHOT","${git.commit.id.describe}"));
        assertEquals("0.0.0-SNAPSHOT",ApplicationInfo.version("0.0.0-SNAPSHOT",null));
        assertEquals("0.0.0-SNAPSHOT",ApplicationInfo.version("0.0.0-SNAPSHOT","none"));
    }
}
