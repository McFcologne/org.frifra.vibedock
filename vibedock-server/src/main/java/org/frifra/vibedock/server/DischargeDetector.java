package org.frifra.vibedock.server;

import java.util.*;
import java.util.function.Function;

/**
 * The discharges of a display's battery found in its smoothed voltage (BatterySeries), see
 * docs/design/battery-learning.md. A rise of the voltage proves nothing, so only a decline that is confirmed counts:
 * <ul>
 * <li>a decline starts with the first measurable fall (phase decline_candidate) and goes on until the voltage rises
 * measurably above its lowest point, or the run ends;</li>
 * <li>it is confirmed when it reaches the limit range (a reading below {@value #LIMIT_MV} mV), or when the run ends in a failure:
 * the last reading lies in the limit range and the next sample belongs to a start after power was lost
 * ("power-on" or "brownout") of the display;</li>
 * <li>a decline that ends before is dropped: it teaches nothing and is not called charging.</li>
 * </ul>
 */
final class DischargeDetector {
    private DischargeDetector(){}
    /** The limit range: everything below the lowest step of the firmware's base curve, for this device only. */
    static final int LIMIT_MV=3520;
    /** The voltage has risen measurably above the lowest point of a decline: the decline is over. */
    static final double RISE_MV=30;
    /** Readings before the first candidate in which the fall began (30 minutes at five minutes each). */
    static final int LOOK_BACK=6;
    static final String FAILURE="failure",LIMIT="limit";

    /**
     * One confirmed discharge: the smoothed voltage from the start of the decline (the highest point before the fall) to its
     * end (the lowest point, or the last reading of a run that ended in a failure). endedBy is "failure" (the cell was
     * empty: the anchor of 0 %) or "limit" (the limit range was reached, the run went on).
     */
    record Discharge(String boot,long startAt,double startMv,long endAt,double endMv,String endedBy,List<BatterySeries.Point> points) {}

    /** startKind maps a start (boot id) to its kind (DeviceDiagnostics), null when unknown. */
    static List<Discharge> find(List<DeviceMetrics.Sample> samples,Function<String,String> startKind){
        var points=BatterySeries.of(samples);
        var found=new ArrayList<Discharge>();
        int i=0;
        while(i<points.size()){
            if(points.get(i).smoothed()==null){i++;continue;}
            int a=i,b=i;
            while(b+1<points.size()&&continues(points.get(b),points.get(b+1)))b++;
            segment(samples,points,a,b,startKind,found);
            i=b+1;
        }
        return found;
    }
    private static boolean continues(BatterySeries.Point before,BatterySeries.Point next){
        return next.smoothed()!=null&&Objects.equals(before.boot(),next.boot())&&next.at()-before.at()<=BatterySeries.GAP_MINUTES*60_000L;
    }
    private static void segment(List<DeviceMetrics.Sample> samples,List<BatterySeries.Point> points,int a,int b,Function<String,String> startKind,List<Discharge> found){
        int start=-1,low=-1,floor=a;
        for(int i=a;i<=b;i++){
            var p=points.get(i);
            if(start<0){
                if(!BatterySeries.CANDIDATE.equals(p.phase()))continue;
                start=i;for(int j=Math.max(floor,i-LOOK_BACK);j<i;j++)if(points.get(j).smoothed()>=points.get(start).smoothed())start=j;
                low=i;continue;
            }
            if(p.smoothed()<points.get(low).smoothed())low=i;
            if(p.smoothed()>=points.get(low).smoothed()+RISE_MV){
                close(samples,points,start,low,false,b,startKind,found);floor=low+1;start=-1;low=-1;
            }
        }
        if(start>=0)close(samples,points,start,low,true,b,startKind,found);
    }
    /** The decline from start to low ended by a rise, or (runEnded) with the run at index b. */
    private static void close(List<DeviceMetrics.Sample> samples,List<BatterySeries.Point> points,int start,int low,boolean runEnded,int b,Function<String,String> startKind,List<Discharge> found){
        int end=runEnded?b:low;
        int lowest=Integer.MAX_VALUE;
        for(int i=start;i<=end;i++)if(points.get(i).raw()!=null)lowest=Math.min(lowest,points.get(i).raw());
        boolean limit=lowest<LIMIT_MV;
        boolean failure=runEnded&&points.get(b).raw()!=null&&points.get(b).raw()<LIMIT_MV&&failureFollows(samples,points,b,startKind);
        if(!limit&&!failure)return;
        found.add(new Discharge(points.get(start).boot(),points.get(start).at(),points.get(start).smoothed(),points.get(end).at(),points.get(end).smoothed(),
                failure?FAILURE:LIMIT,List.copyOf(points.subList(start,end+1))));
    }
    /** The sample after the run belongs to a start that follows a loss of power. */
    private static boolean failureFollows(List<DeviceMetrics.Sample> samples,List<BatterySeries.Point> points,int b,Function<String,String> startKind){
        if(b+1>=samples.size())return false;
        var next=samples.get(b+1);var last=samples.get(b);
        if(next.boot()==null||next.boot().equals(last.boot()))return false;
        String kind=startKind.apply(next.boot());
        return "power-on".equals(kind)||"brownout".equals(kind);
    }
}
