package org.frifra.vibedock.server;

/**
 * A complete display design, used for the default and for device overrides alike. mood is the emoji bottom right
 * ("" = none); weather is the place for the forecast (null = none).
 */
public record Design(Scheme scheme,String mood,WeatherService.Place weather,Layout layout) {
    static final String DEFAULT_MOOD="🤖";
    public static final Design DEFAULT=new Design(Scheme.MIDNIGHT,DEFAULT_MOOD,null,Layout.DEFAULT);
    public Design {
        scheme=scheme==null?Scheme.MIDNIGHT:scheme;
        mood=mood==null||mood.strip().equals(Twemoji.NONE)?"":mood.strip();
        if(mood.length()>32||mood.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid emoji");
        if(weather!=null&&weather.none())weather=null;
        WeatherService.validate(weather);
        layout=layout==null?Layout.DEFAULT:layout;
    }
    public Design(Scheme scheme,String mood,WeatherService.Place weather){this(scheme,mood,weather,Layout.DEFAULT);}
}
