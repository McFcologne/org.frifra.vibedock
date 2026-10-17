package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirmwareVersionTest {
    @org.junit.jupiter.api.Test void protocolUpgradesRequireAKnownNewerVersion(){
        org.junit.jupiter.api.Assertions.assertTrue(FirmwareVersion.knownNewer("0.1.1","0.1.0"));
        org.junit.jupiter.api.Assertions.assertTrue(FirmwareVersion.knownNewer("0.1.0-1-gabcdef-dirty","0.1.0"));
        for(String version:new String[]{null,"0.1.0","0.1.0-dirty","0.0.9","unknown","999999999999999999999.1.0"})
            org.junit.jupiter.api.Assertions.assertFalse(FirmwareVersion.knownNewer(version,"0.1.0"));
    }

    @Test void offersOnlyNewerFirmware() {
        assertTrue(FirmwareVersion.newer("0.2.5","0.2.4"));
        assertTrue(FirmwareVersion.newer("0.2.4-33-g2051f44","0.2.4-29-g92b2427"));
        assertTrue(FirmwareVersion.newer("0.2.5","0.2.4-33-g2051f44"));
        assertTrue(FirmwareVersion.newer("0.10.0","0.9.9"));
        assertTrue(FirmwareVersion.newer("0.2.0",null));
        // A device with a newer build than the server's gets no downgrade.
        assertFalse(FirmwareVersion.newer("0.2.4-29-g92b2427","0.2.4-33-g2051f44"));
        assertFalse(FirmwareVersion.newer("0.2.4","0.2.4-1-gabcdef0"));
        assertFalse(FirmwareVersion.newer("0.2.4","0.2.4"));
        assertFalse(FirmwareVersion.newer("v0.2.4-3-gabcdef0-dirty","0.2.4-3-gabcdef0"));
        // Unknown forms keep the old rule: any other version.
        assertTrue(FirmwareVersion.newer("0.0.0-SNAPSHOT","0.2.4"));
        assertFalse(FirmwareVersion.newer(null,"0.2.4"));
    }
}
