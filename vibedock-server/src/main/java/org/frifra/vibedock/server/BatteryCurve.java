package org.frifra.vibedock.server;

import java.util.*;

/**
 * What a device learned about its own cell: the percentage that belongs to a smoothed voltage, from its most recent
 * confirmed discharge (DischargeDetector). The load of a display is steady, so the time since the start of the decline
 * stands for the charge used: 100 % at the start, 0 % at a failure (the cell was empty), and {@value #LIMIT_PERCENT} % at
 * the end of a discharge that only reached the limit range. Never taken from another device (see docs/design/battery-learning.md).
 */
final class BatteryCurve {
    /** What is left when only the limit range was reached and the run went on. */
    static final int LIMIT_PERCENT=10;
    static final String LEARNED="learned";
    private final double[] volts,percents;private final String basis;
    private BatteryCurve(double[] volts,double[] percents,String basis){this.volts=volts;this.percents=percents;this.basis=basis;}

    /** The curve of the most recent discharge that ended in a failure, else of the most recent one; null without any. */
    static BatteryCurve of(List<DischargeDetector.Discharge> discharges){
        DischargeDetector.Discharge use=null;
        for(var d:discharges)if(use==null||rank(d)>rank(use)||rank(d)==rank(use)&&d.endAt()>use.endAt())use=d;
        if(use==null)return null;
        double bottom=use.endedBy().equals(DischargeDetector.FAILURE)?0:LIMIT_PERCENT;
        var points=use.points();long span=use.endAt()-use.startAt();
        if(points.size()<2||span<=0)return null;
        var v=new ArrayList<Double>();var p=new ArrayList<Double>();
        double floor=Double.MAX_VALUE;
        for(var point:points){
            double volt=Math.min(floor,point.smoothed());floor=volt;
            double percent=100-(100-bottom)*(point.at()-use.startAt())/span;
            if(!v.isEmpty()&&volt>=v.get(v.size()-1)&&volt==v.get(v.size()-1)){p.set(p.size()-1,(p.get(p.size()-1)+percent)/2);continue;}
            v.add(volt);p.add(percent);
        }
        if(v.size()<2)return null;
        return new BatteryCurve(v.stream().mapToDouble(Double::doubleValue).toArray(),p.stream().mapToDouble(Double::doubleValue).toArray(),use.endedBy());
    }
    private static int rank(DischargeDetector.Discharge d){return d.endedBy().equals(DischargeDetector.FAILURE)?1:0;}

    /** The percentage for a smoothed voltage, 0 to 100. */
    int percent(double millivolts){
        double result;
        if(millivolts>=volts[0])result=percents[0];
        else if(millivolts<=volts[volts.length-1])result=percents[percents.length-1];
        else{
            int i=0;while(i+1<volts.length&&volts[i+1]>millivolts)i++;
            double high=volts[i],low=volts[i+1];
            result=percents[i]+(percents[i+1]-percents[i])*(high-millivolts)/(high-low);
        }
        return (int)Math.max(0,Math.min(100,Math.round(result)));
    }
    /** "failure" or "limit": what the lower end of the curve rests on. */
    String basis(){return basis;}
}
