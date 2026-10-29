package org.frifra.vibedock.server;

/**
 * A complete display design, used for the default and for device overrides alike. mood is the emoji bottom right
 * ("" = none); weather is the place for the forecast (null = none). photos: instead of the status, uploaded photos
 * (null = the status only).
 */
public record Design(Scheme scheme,String mood,WeatherService.Place weather,Layout layout,Photos photos,
                     @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Icon icon) {
    /** null on Design means the original emoticon/no-icon selection; a catalog reference remains fixed across swipes. */
    public record Icon(String mode,String theme,String id) {
        public Icon {
            if("theme-default".equals(mode)){
                if(theme!=null||id!=null)throw new IllegalArgumentException("Theme default follows the selected theme");
            }else if("theme-catalog".equals(mode)){
                if(theme==null||id==null||!theme.matches("[a-z][a-z0-9._-]{0,79}")||!id.matches("[a-z][a-z0-9._-]{0,79}"))
                    throw new IllegalArgumentException("Choose a theme and an icon");
            }else throw new IllegalArgumentException("Unknown icon selection mode");
        }
    }
    /**
     * A slideshow of photos of the {@link PhotoLibrary}, each shown for seconds, in their order or shuffled. from/to
     * ("HH:mm", both or neither): the photos only in that time of day (e.g. 22:00 to 07:00), the status otherwise.
     */
    /**
     * Photos instead of the usage monitor. paused: the usage monitor is chosen; the selection and settings are kept
     * for switching back.
     */
    public record Photos(java.util.List<String> images,int seconds,boolean shuffle,String from,String to,boolean paused) {
        public Photos(java.util.List<String> images,int seconds,boolean shuffle,String from,String to){this(images,seconds,shuffle,from,to,false);}
        public static final java.util.List<Integer> DURATIONS=java.util.List.of(10,30,60,300,900,3600);
        public Photos {
            images=images==null?java.util.List.of():java.util.List.copyOf(images);
            if(images.size()>200||images.stream().anyMatch(i->i==null||!PhotoLibrary.ID.matcher(i).matches()))throw new IllegalArgumentException("Invalid photo selection");
            if(seconds<10||seconds>86400)throw new IllegalArgumentException("Show each photo for 10 seconds to a day");
            from=from==null||from.isBlank()?null:from.strip();to=to==null||to.isBlank()?null:to.strip();
            if((from==null)!=(to==null))throw new IllegalArgumentException("Give the time of day from and to, or neither");
            if(from!=null&&(!from.matches("([01]\\d|2[0-3]):[0-5]\\d")||!to.matches("([01]\\d|2[0-3]):[0-5]\\d")||from.equals(to)))
                throw new IllegalArgumentException("Times of day as HH:mm, from and to different");
        }
        /** Whether the photos are shown at this time of day (otherwise the usage monitor). */
        public boolean active(java.time.LocalTime now){
            if(paused||images.isEmpty())return false;
            if(from==null)return true;
            var a=java.time.LocalTime.parse(from);var b=java.time.LocalTime.parse(to);
            return a.isBefore(b)?!now.isBefore(a)&&now.isBefore(b):!now.isBefore(a)||now.isBefore(b);
        }
    }
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
    public Design(Scheme scheme,String mood,WeatherService.Place weather,Layout layout){this(scheme,mood,weather,layout,null);}
    public Design(Scheme scheme,String mood,WeatherService.Place weather,Layout layout,Photos photos){this(scheme,mood,weather,layout,photos,null);}
    public Design withScheme(Scheme other){return new Design(other,mood,weather,layout,photos,icon);}
    public Design withLayout(Layout other){return new Design(scheme,mood,weather,other,photos,icon);}
}
