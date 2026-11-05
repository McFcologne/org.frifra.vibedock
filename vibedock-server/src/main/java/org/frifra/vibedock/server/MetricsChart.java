package org.frifra.vibedock.server;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

/**
 * A curve chart of metric samples as inline SVG (no script, colours from the administration's style sheet). The line
 * breaks where samples are more than twice the keeping interval apart or a new start begins; a dashed line marks
 * each start.
 */
final class MetricsChart {
    private MetricsChart(){}
    /** One curve: a label, its value per sample (null = not measured) and the unit's name for the axis. */
    record Series(String label,Function<DeviceMetrics.Sample,Number> value) {}
    private static final int W=720,H=170,LEFT=52,RIGHT=8,TOP=10,BOTTOM=22;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /**
     * The chart for samples between from and to (epoch milliseconds, the x axis); scale divides the values for the axis
     * (1000 for bytes shown as kB), zeroBased starts the axis at 0. Empty when no sample has a value.
     */
    static String svg(List<DeviceMetrics.Sample> samples,long from,long to,List<Series> series,double scale,boolean zeroBased,String unit,ZoneId zone){
        double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;boolean any=false;
        for(var s:samples)for(var curve:series){
            Number n=curve.value().apply(s);if(n==null)continue;
            double v=n.doubleValue()/scale;min=Math.min(min,v);max=Math.max(max,v);any=true;
        }
        if(!any||to<=from)return "";
        if(zeroBased)min=0;
        if(max-min<1e-9){max=min+1;}
        double pad=zeroBased?0:(max-min)*0.08;min-=pad;max+=pad;
        double lo=min,hi=max;long span=to-from;
        var out=new StringBuilder();
        out.append("<svg class=\"metric-chart\" viewBox=\"0 0 ").append(W).append(' ').append(H).append("\" role=\"img\" aria-label=\"")
                .append(escape(String.join(", ",series.stream().map(Series::label).toList()))).append("\">");
        double plotW=W-LEFT-RIGHT,plotH=H-TOP-BOTTOM;
        for(int i=0;i<=2;i++){
            double y=TOP+plotH*i/2.0,value=hi-(hi-lo)*i/2.0;
            out.append("<line class=\"grid\" x1=\"").append(LEFT).append("\" x2=\"").append(W-RIGHT).append("\" y1=\"").append(f(y)).append("\" y2=\"").append(f(y)).append("\"/>");
            out.append("<text class=\"axis\" x=\"").append(LEFT-6).append("\" y=\"").append(f(y+4)).append("\" text-anchor=\"end\">").append(label(value,hi-lo)).append("</text>");
        }
        out.append("<text class=\"axis\" x=\"").append(LEFT).append("\" y=\"").append(H-6).append("\">").append(TIME.format(java.time.Instant.ofEpochMilli(from).atZone(zone))).append("</text>");
        out.append("<text class=\"axis\" x=\"").append(W-RIGHT).append("\" y=\"").append(H-6).append("\" text-anchor=\"end\">").append(TIME.format(java.time.Instant.ofEpochMilli(to).atZone(zone))).append("</text>");
        out.append("<text class=\"axis unit\" x=\"").append(LEFT+4).append("\" y=\"").append(TOP+10).append("\">").append(escape(unit)).append("</text>");
        String boot=null;
        for(var s:samples){
            if(s.boot()!=null&&boot!=null&&!s.boot().equals(boot)){
                double x=LEFT+plotW*(s.at()-from)/span;
                out.append("<line class=\"restart\" x1=\"").append(f(x)).append("\" x2=\"").append(f(x)).append("\" y1=\"").append(TOP).append("\" y2=\"").append(H-BOTTOM).append("\"/>");
            }
            if(s.boot()!=null)boot=s.boot();
        }
        int index=0;
        for(var curve:series){
            var path=new StringBuilder();DeviceMetrics.Sample before=null;
            for(var s:samples){
                Number n=curve.value().apply(s);if(n==null){before=null;continue;}
                double x=LEFT+plotW*(s.at()-from)/span,y=TOP+plotH*(hi-n.doubleValue()/scale)/(hi-lo);
                boolean joined=before!=null&&s.at()-before.at()<=2L*DeviceMetrics.INTERVAL_SECONDS*1000+10_000&&Objects.equals(s.boot(),before.boot());
                path.append(joined?" L":" M").append(f(x)).append(' ').append(f(y));
                before=s;
            }
            if(path.length()>0)out.append("<path class=\"curve c").append(index).append("\" d=\"").append(path.substring(1)).append("\"/>");
            index++;
        }
        return out.append("</svg>").toString();
    }
    private static String f(double v){return String.format(Locale.ROOT,"%.1f",v);}
    private static String label(double v,double span){return span>=20?String.format(Locale.ROOT,"%.0f",v):String.format(Locale.ROOT,"%.1f",v);}
    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
}
