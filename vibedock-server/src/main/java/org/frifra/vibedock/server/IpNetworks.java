package org.frifra.vibedock.server;

import java.net.InetAddress;
import java.util.List;

final class IpNetworks {
    record Network(byte[] address, int bits) {
        boolean matches(String peer) {
            try {
                byte[] other = literal(peer);
                if (other.length != address.length) return false;
                for (int i=0; i<bits; i++) {
                    int mask=1 << (7-i%8);
                    if ((address[i/8] & mask) != (other[i/8] & mask)) return false;
                }
                return true;
            } catch (IllegalArgumentException e) { return false; }
        }
    }
    static byte[] literal(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("IP address missing");
        try {
            if (text.contains(":")) {
                if (!text.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Invalid IPv6 address");
            } else {
                String[] parts=text.split("\\.", -1);
                if (parts.length != 4) throw new IllegalArgumentException("Four IPv4 octets required");
                for (String p:parts) {
                    if (!p.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(p)>255)
                        throw new IllegalArgumentException("Invalid IPv4 address");
                }
            }
            return InetAddress.getByName(text).getAddress();
        } catch (Exception e) { throw new IllegalArgumentException("Invalid IP address: " + text); }
    }
    static Network parse(String value) {
        String[] parts=value.trim().split("/", -1);
        if (parts.length>2) throw new IllegalArgumentException("Invalid IP range");
        byte[] address=literal(parts[0]);
        int bits;
        try { bits=parts.length==1 ? address.length*8 : Integer.parseInt(parts[1]); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid CIDR prefix length"); }
        if (bits<0 || bits>address.length*8) throw new IllegalArgumentException("Invalid CIDR prefix length");
        return new Network(address,bits);
    }
    /** Widest network a device may report for itself: a home or office LAN, never a large part of the address space. */
    static final int MIN_SUBNET_BITS=16;
    /** Bind the reported IPv4 mask to the administrator's pairing target, never to a later pull. */
    static String subnet(String ip,String mask) {
        byte[] address=literal(ip),bytes=literal(mask);
        if(address.length!=4||bytes.length!=4)throw new IllegalArgumentException("An IPv4 subnet mask is required");
        int bits=0;boolean zero=false;
        for(byte value:bytes)for(int bit=7;bit>=0;bit--){
            if((value&(1<<bit))==0)zero=true;
            else{if(zero)throw new IllegalArgumentException("Subnet mask must be contiguous");bits++;}
        }
        if(bits<MIN_SUBNET_BITS)throw new IllegalArgumentException("Subnet masks wider than /"+MIN_SUBNET_BITS+" are not accepted");
        return ip+"/"+bits;
    }
    static boolean allows(List<String> ranges,String peer) {
        return ranges.stream().map(IpNetworks::parse).anyMatch(n -> n.matches(peer));
    }
}
