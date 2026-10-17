package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubnetTest {
    @Test void masksDefineTheActualNetworkRatherThanAnAssumed24BitPrefix(){
        var network=IpNetworks.parse(IpNetworks.subnet("192.168.17.5","255.255.240.0"));
        assertTrue(network.matches("192.168.16.1"));assertTrue(network.matches("192.168.31.254"));
        assertFalse(network.matches("192.168.15.254"));assertFalse(network.matches("192.168.32.1"));
        assertEquals("192.168.17.5/32",IpNetworks.subnet("192.168.17.5","255.255.255.255"));
    }
    @Test void malformedAndUnrestrictedMasksCannotExpandTheTrustBoundary(){
        for(String mask:new String[]{null,"","0.0.0.0","255.0.255.0","255.255.255.1","example.com","::1"})
            assertThrows(IllegalArgumentException.class,()->IpNetworks.subnet("192.168.1.50",mask));
        assertThrows(IllegalArgumentException.class,()->IpNetworks.subnet("::1","255.255.255.0"));
    }
    @Test void devicesCannotClaimNetworksWiderThanASlash16(){
        for(String mask:new String[]{"128.0.0.0","255.0.0.0","255.254.0.0"})
            assertThrows(IllegalArgumentException.class,()->IpNetworks.subnet("10.1.2.3",mask),mask);
        assertEquals("10.1.2.3/16",IpNetworks.subnet("10.1.2.3","255.255.0.0"));
    }
}
