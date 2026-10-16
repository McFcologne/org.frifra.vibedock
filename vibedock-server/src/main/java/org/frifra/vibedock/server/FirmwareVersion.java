package org.frifra.vibedock.server;

import java.util.regex.Pattern;

/**
 * Orders firmware versions as {@code scripts/version.sh} writes them: {@code 0.2.4}, {@code 0.2.4-33-g2051f44}
 * (33 commits after 0.2.4), optionally with {@code v} in front and {@code -dirty} behind. An update is offered only to
 * a device whose firmware is older than the server's, never as a downgrade.
 */
public final class FirmwareVersion {
    private static final Pattern FORM=Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(\\d+))?(?:-g[0-9a-f]+)?(?:-dirty)?");
    private FirmwareVersion(){}
    /** True if {@code candidate} is newer than {@code installed}; a device without a version (before 0.2) is older. */
    public static boolean newer(String candidate,String installed){
        if(candidate==null)return false;
        if(installed==null)return true;
        long[] a=parse(candidate),b=parse(installed);
        if(a==null||b==null)return !candidate.equals(installed);  // unknown forms: as before, any other version
        for(int i=0;i<a.length;i++)if(a[i]!=b[i])return a[i]>b[i];
        return false;
    }
    private static long[] parse(String version){
        var m=FORM.matcher(version.trim());
        if(!m.matches())return null;
        return new long[]{Long.parseLong(m.group(1)),Long.parseLong(m.group(2)),Long.parseLong(m.group(3)),m.group(4)==null?0:Long.parseLong(m.group(4))};
    }
}
