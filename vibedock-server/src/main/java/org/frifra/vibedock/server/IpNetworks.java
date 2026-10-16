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
    static boolean allows(List<String> ranges,String peer) {
        return ranges.stream().map(IpNetworks::parse).anyMatch(n -> n.matches(peer));
    }
}
