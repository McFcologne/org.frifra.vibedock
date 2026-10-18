package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TlsPinControllerTest {
    @Test void statusShowsValidatedPinAndDoesNotRetainItAfterLookupFails() throws Exception {
        var addresses=mock(ServerAddresses.class);var trust=mock(TlsTrust.class);
        when(addresses.external()).thenReturn("https://external.example.org");
        when(trust.recentInspection(anyString())).thenReturn(new TlsTrust.Inspection(new byte[]{1},"ab".repeat(20)))
                .thenThrow(new IllegalArgumentException("not reachable"));
        var mvc=MockMvcBuilders.standaloneSetup(new TlsPinController(trust,addresses)).build();
        mvc.perform(get("/admin/tls-pin")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.known").value(true)).andExpect(jsonPath("$.fingerprint").value("ab".repeat(20)));
        mvc.perform(get("/admin/tls-pin")).andExpect(jsonPath("$.known").value(false)).andExpect(jsonPath("$.fingerprint").isEmpty());
        when(addresses.external()).thenReturn("");
        mvc.perform(get("/admin/tls-pin")).andExpect(jsonPath("$.known").value(false));
        verify(trust,times(2)).recentInspection(anyString());
    }
    @Test void bothPairingEndpointsDefaultToSendingPinAndAllowExplicitOptOut() throws Exception {
        var pairing=mock(ClientPairing.class);var addresses=mock(ServerAddresses.class);
        var servers=List.of("http://192.168.1.10:10002","https://external.example.org");
        when(addresses.announced(any())).thenReturn(servers);
        // Rejection after invoking the service avoids creating a synthetic registered device in this wiring test.
        when(pairing.invite(anyString(),anyInt(),anyString(),anyString(),anyString(),anyList(),anyBoolean()))
                .thenThrow(new IllegalArgumentException("synthetic rejection"));
        when(pairing.setUp(anyString(),anyString(),anyString(),anyList(),anyBoolean()))
                .thenReturn(new ClientPairing.Setup(Map.of("anchor","AQID"),List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new DeviceAdminController(null,pairing,null,addresses,null,null,null,null,null,null),
                new FirmwareController(null,null,pairing,addresses,"UTC")).build();
        mvc.perform(post("/admin/clients/invite").param("ip","192.168.1.20").param("code","123456")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/clients/invite").param("ip","192.168.1.20").param("code","123456").param("omitTlsPin","on")).andExpect(status().is3xxRedirection());
        verify(pairing).invite("192.168.1.20",80,"123456","","",servers,true);
        verify(pairing).invite("192.168.1.20",80,"123456","","",servers,false);
        mvc.perform(post("/admin/firmware/setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"vibedock-123456789abc\",\"name\":\"Test\",\"board\":\"vibedock-m1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/firmware/setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"vibedock-123456789abc\",\"name\":\"Test\",\"board\":\"vibedock-m1\",\"omitTlsPin\":true}"))
                .andExpect(status().isOk());
        verify(pairing).setUp("vibedock-123456789abc","Test","vibedock-m1",servers,true);
        verify(pairing).setUp("vibedock-123456789abc","Test","vibedock-m1",servers,false);
    }
}
