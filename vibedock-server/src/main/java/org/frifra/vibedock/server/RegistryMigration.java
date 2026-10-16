package org.frifra.vibedock.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

/**
 * Reads clients.json of version 2 (settings and default display as loose top-level fields, per-field device
 * overrides or a "design" snapshot per device) into version 3. Every device keeps exactly what it showed before.
 */
final class RegistryMigration {
    private RegistryMigration(){}

    static ClientRegistry.State fromVersion2(JsonNode root,ObjectMapper json) throws IOException {
        var server=new ClientRegistry.Server(text(root,"internalUrl"),text(root,"externalUrl"),root.path("pollSeconds").asInt(ClientRegistry.DEFAULT_POLL));
        String emoji=text(root,"emoji");
        var defaults=new Design(scheme(text(root,"scheme")),emoji.isBlank()?Design.DEFAULT_MOOD:emoji,
                place(root.get("weather"),json),layout(root.get("layout")));
        List<ClientRegistry.Device> devices=new ArrayList<>();
        for(JsonNode c:root.path("clients")){
            Design own;
            if(c.hasNonNull("design")){
                JsonNode d=c.get("design");
                own=new Design(scheme(text(d,"scheme")),text(d,"emoji"),place(d.get("weather"),json),layout(d.get("layout")));
            }else{
                String ownEmoji=text(c,"emoji"),ownScheme=text(c,"scheme");var ownWeather=place(c.get("weather"),json);
                boolean weatherSet=c.hasNonNull("weather");
                own=ownEmoji.isBlank()&&ownScheme.isBlank()&&!weatherSet?null:new Design(
                        ownScheme.isBlank()?defaults.scheme():scheme(ownScheme),ownEmoji.isBlank()?defaults.mood():ownEmoji,
                        weatherSet?ownWeather:defaults.weather(),defaults.layout());
            }
            devices.add(new ClientRegistry.Device(text(c,"identifier"),text(c,"name"),text(c,"keyHash"),text(c,"ip"),c.path("port").asInt(80),
                    c.path("pairedAt").asLong(),c.hasNonNull("lastPullAt")?c.get("lastPullAt").asLong():null,
                    c.hasNonNull("lastPullIp")?c.get("lastPullIp").asText():null,own,false,null));
        }
        return new ClientRegistry.State(ClientRegistry.VERSION,server,defaults,devices);
    }
    private static String text(JsonNode node,String field){return node.hasNonNull(field)?node.get(field).asText():"";}
    private static Scheme scheme(String id){return id.isBlank()?Scheme.MIDNIGHT:Scheme.parse(id);}
    /** "-" as name meant "no weather". */
    private static WeatherService.Place place(JsonNode node,ObjectMapper json) throws IOException {
        if(node==null||node.isNull())return null;
        var place=json.treeToValue(node,WeatherService.Place.class);return place.none()?null:place;
    }
    /** Version 2 layouts: order of "first", "second" and "status", the sources of both usage bands and moodLeft. */
    private static Layout layout(JsonNode node){
        if(node==null||node.isNull())return Layout.DEFAULT;
        List<Layout.Band> bands=new ArrayList<>();
        for(JsonNode name:node.path("order")){
            bands.add(switch(name.asText()){
                case "first","second" -> {
                    List<String> sources=new ArrayList<>();node.path(name.asText()).forEach(s->sources.add(s.asText()));yield new Layout.Usage(sources);
                }
                case "status" -> new Layout.Context(node.path("moodLeft").asBoolean());
                default -> throw new IllegalArgumentException("Unknown band "+name.asText());
            });
        }
        return new Layout(bands);
    }
}
