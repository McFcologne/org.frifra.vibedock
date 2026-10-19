package org.frifra.vibedock.server;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuotaHeaderTest {
    private static UsageService.Provider provider(String status,double used){
        return new UsageService.Provider(status,100L,new ProviderClient.Window(used,1000),new ProviderClient.Window(100,2000));
    }
    @Test void optInIsExplicitAndPreviewNeverNeedsTheHeader(){
        assertFalse(QuotaHeader.requested(null,null));assertFalse(QuotaHeader.requested("0",null));
        assertFalse(QuotaHeader.requested("true",null));assertTrue(QuotaHeader.requested("1",null));
        assertFalse(QuotaHeader.requested("1","1"));
    }
    @Test void onlyFittedFreshSourcesAreIncludedAndUnchangedValuesHaveStableIdentity(){
        var layout=new Layout(List.of(new Layout.Usage(List.of("first")),new Layout.Context(false)));
        var values=new UsageService.Snapshot(1,100,Map.of("first",provider("ok",12.34),"hidden",provider("ok",25)));
        String header=QuotaHeader.create(values,layout);
        assertTrue(header.matches("v1;[0-9a-f]{12}f:8766;[0-9a-f]{12}w:0"),header);
        assertFalse(header.contains("first")); assertFalse(header.contains("hidden"));
        assertEquals(header,QuotaHeader.create(new UsageService.Snapshot(1,101,values.providers()),layout));
        assertEquals("v1",QuotaHeader.create(new UsageService.Snapshot(1,100,Map.of("first",provider("rate_limited",12))),layout));
        assertEquals("v1",QuotaHeader.create(new UsageService.Snapshot(1,100,Map.of("first",provider("stale",12))),layout));
        assertEquals(header,QuotaHeader.create(new UsageService.Snapshot(1,100,Map.of("first",provider("partial",12.34))),layout));
    }
    @Test void invalidOrExpiredWindowsAreAbsentAndEverySourceGetsIndependentIds(){
        var layout=new Layout(List.of(new Layout.Usage(List.of("first","second")),new Layout.Context(false)));
        var invalid=new UsageService.Provider("ok",100L,new ProviderClient.Window(Double.NaN,0),new ProviderClient.Window(25,99));
        assertEquals("v1",QuotaHeader.create(new UsageService.Snapshot(1,100,Map.of("first",invalid)),layout));
        String header=QuotaHeader.create(new UsageService.Snapshot(1,100,Map.of("first",provider("ok",0),"second",provider("ok",100))),layout);
        var fields=header.split(";");assertEquals(5,fields.length);
        assertNotEquals(fields[1].split(":")[0],fields[3].split(":")[0]);
        assertTrue(fields[1].endsWith(":10000"));assertTrue(fields[3].endsWith(":0"));
    }
    @Test void maximumLayoutFitsTheBoundedClientParser(){
        var bands=new ArrayList<Layout.Band>();var values=new HashMap<String,UsageService.Provider>();
        for(int i=0;i<5;i++){
            var left="source"+i+"a";var right="source"+i+"b";
            bands.add(new Layout.Usage(List.of(left,right)));values.put(left,provider("ok",0));values.put(right,provider("ok",0));
        }
        bands.add(new Layout.Context(false));
        String header=QuotaHeader.create(new UsageService.Snapshot(1,100,values),new Layout(bands));
        assertEquals(21,header.split(";").length);assertTrue(header.length()<=420);
    }
}
