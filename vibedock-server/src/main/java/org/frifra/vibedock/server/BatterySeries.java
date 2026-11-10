package org.frifra.vibedock.server;

import java.util.*;

/**
 * The battery voltage of a display, smoothed (derived when reading; the stored raw values never change, see
 * docs/design/battery-learning.md). Per run (boot id) and without gaps of more than {@value #GAP_MINUTES} minutes: the
 * median of three readings against outliers (the radio pulls the voltage down for a moment), then the mean of the last
 * three medians. The first reading of a run does not count (the board has just started, the value is not settled).
 * The slope is the fall or rise over the last {@value #SLOPE_MINUTES} minutes in mV per hour, given only once the readings
 * cover these minutes; the phase is neutral:
 * {@code decline_candidate} for a fall beyond the noise, otherwise {@code flat}. A rise is never called charging.
 */
final class BatterySeries {
    private BatterySeries(){}
    static final int GAP_MINUTES=15,SLOPE_MINUTES=30,MIN_POINTS=3;
    /** A fall of at least this much per hour over the slope window (50 mV in 30 minutes, five reading steps) is a decline candidate. */
    static final double DECLINE_MV_PER_HOUR=100;
    static final String FLAT="flat",CANDIDATE="decline_candidate";

    /** smoothed and slope are null where the voltage is not measured, or the reading does not count yet. */
    record Point(long at,String boot,Integer raw,Double smoothed,Double slope,String phase) {}

    /** One point per sample, in the order of the samples (oldest first). */
    static List<Point> of(List<DeviceMetrics.Sample> samples){
        var points=new ArrayList<Point>(samples.size());
        var raws=new ArrayList<Double>();var times=new ArrayList<Long>();var medians=new ArrayList<Double>();var smooth=new ArrayList<Double>();
        String boot=null;long last=0;boolean started=false,first=true;
        for(var s:samples){
            Integer mv=s.millivolts();
            if(first||mv==null||!Objects.equals(boot,s.boot())||s.at()-last>GAP_MINUTES*60_000L){raws.clear();times.clear();medians.clear();smooth.clear();started=false;}
            first=false;boot=s.boot();last=s.at();
            if(mv==null){points.add(new Point(s.at(),s.boot(),null,null,null,FLAT));continue;}
            if(!started){started=true;points.add(new Point(s.at(),s.boot(),mv,null,null,FLAT));continue;}
            raws.add(mv.doubleValue());times.add(s.at());
            int m=raws.size();
            medians.add(m>=3?median3(raws.get(m-3),raws.get(m-2),raws.get(m-1)):raws.get(m-1));
            int from=Math.max(0,medians.size()-3);double sum=0;for(int i=from;i<medians.size();i++)sum+=medians.get(i);
            smooth.add(sum/(medians.size()-from));
            int index=smooth.size()-1,j=index;
            while(j>0&&times.get(index)-times.get(j)<SLOPE_MINUTES*60_000L)j--;
            Double slope=null;
            // Only once the readings really cover the window: a short fall of ten minutes is no decline.
            if(index-j+1>=MIN_POINTS&&times.get(index)-times.get(j)>=SLOPE_MINUTES*60_000L)
                slope=(smooth.get(index)-smooth.get(j))/((times.get(index)-times.get(j))/3_600_000.0);
            points.add(new Point(s.at(),s.boot(),mv,smooth.get(index),slope,slope!=null&&slope<=-DECLINE_MV_PER_HOUR?CANDIDATE:FLAT));
        }
        return points;
    }
    private static double median3(double a,double b,double c){return Math.max(Math.min(a,b),Math.min(Math.max(a,b),c));}
}
