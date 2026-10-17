package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import java.net.http.HttpClient;
import java.time.Clock;

/**
 * DEMO_USAGE=true, for tests without real accounts (no rate limits): every data source reports made-up usage that
 * grows within its 5-hour and weekly window and starts again with the next one, also without a sign-in. No provider
 * is asked. Displays pair and pull as usual.
 */
@Configuration
@ConditionalOnProperty(name="vibedock.demo-usage",havingValue="true")
class DemoUsage {
    @Bean @Primary ProviderClient demoProviders(HttpClient http,ObjectMapper json,Clock clock){
        LoggerFactory.getLogger(DemoUsage.class).warn("DEMO_USAGE is on: made-up usage readings, no provider is asked");
        return new ProviderClient(http,json){
            @Override public Reading fetch(String provider,SettingsStore.Credential credential){
                long now=clock.instant().getEpochSecond(),five=5*3600,week=7*86400;
                int seed=Math.floorMod((provider+"/"+credential.token()+"/"+credential.accountId()).hashCode(),1000);
                long fiveStart=now-Math.floorMod(now+seed*37L,five),weekStart=now-Math.floorMod(now+seed*611L,week);
                double fiveUsed=Math.min(100,seed%35+(now-fiveStart)*70.0/five),weekUsed=Math.min(100,seed%25+(now-weekStart)*60.0/week);
                return new Reading(new Window(fiveUsed,fiveStart+five,five),new Window(weekUsed,weekStart+week,week));
            }
        };
    }
}
