package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** When displays pull next: after the next poll of the sources they show, pending while those are being asked. */
class PullScheduleTest {
    final ObjectMapper json=new ObjectMapper();

    @Test void theNextPollOfTheShownSourcesDecidesAndAPollInProgressIsPending(@TempDir Path dir) throws Exception {
        var settings=new SettingsStore(dir.toString(),json);
        settings.save(new SettingsStore.Settings(List.of(),new SettingsStore.Credential("token",""),new SettingsStore.Credential("",""),List.of()));
        var client=mock(ProviderClient.class);var clock=mock(Clock.class);
        Instant start=Instant.parse("2026-10-13T20:00:00Z");when(clock.instant()).thenReturn(start);
        var service=new UsageService(settings,client,mock(DeviceSender.class),clock);
        // Never polled: nothing to wait for, the display's own limit applies.
        assertEquals(new UsageService.Schedule(Long.MAX_VALUE,false),service.schedule(List.of("codex")));
        var seen=new ArrayList<UsageService.Schedule>();
        when(client.fetch(eq("codex"),any())).thenAnswer(call->{
            seen.add(service.schedule(List.of("codex")));  // while the provider is being asked
            return new ProviderClient.Reading(new ProviderClient.Window(20,1900000000),null);
        });
        service.refresh();
        assertTrue(seen.getFirst().pending(),"asked but not yet published");
        long now=start.getEpochSecond();
        var next=service.schedule(List.of("codex"));
        assertFalse(next.pending());
        // The next full-minute poll, plus the time a collection takes and two seconds.
        assertTrue(next.at()>=now+60+3&&next.at()<=now+60+5,"at "+(next.at()-now));
        // A source the display does not show changes nothing; an unknown one neither.
        assertEquals(Long.MAX_VALUE,service.schedule(List.of("claude","missing")).at());
        // Due but not yet asked: pending, back shortly after the collection should be done.
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        var due=service.schedule(List.of("codex"));
        assertTrue(due.pending());assertTrue(due.at()>=now+62&&due.at()<=now+65);
    }
    @Test void aDisplayAnnouncedLaterCountsAsMissingOnlyWellAfterThat(@TempDir Path dir) throws Exception {
        var clock=mock(Clock.class);Instant start=Instant.parse("2026-10-13T20:00:00Z");when(clock.instant()).thenReturn(start);
        var registry=new ClientRegistry(dir.toString(),json,clock);
        registry.paired("vibedock-112233","Test","ab".repeat(32),"192.168.1.10",80);
        registry.pulled("vibedock-112233","192.168.1.10");
        int interval=registry.pollSeconds();
        // Without an announcement: missing after three poll intervals, as before.
        when(clock.instant()).thenReturn(start.plusSeconds(3L*interval+1));
        assertEquals("Updates missing",registry.get("vibedock-112233").status());
        when(clock.instant()).thenReturn(start);
        registry.expectPull("vibedock-112233",600);
        when(clock.instant()).thenReturn(start.plusSeconds(600+2L*interval));
        assertEquals("Connected",registry.get("vibedock-112233").status());
        when(clock.instant()).thenReturn(start.plusSeconds(600+2L*interval+1));
        assertEquals("Updates missing",registry.get("vibedock-112233").status());
    }
    @Test void headerFormat(){
        assertEquals("58; state=current",new PullSchedule.Next(58,false).header());
        assertEquals("3; state=pending",new PullSchedule.Next(3,true).header());
    }
}
