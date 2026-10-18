package org.frifra.vibedock.server;

import org.springframework.stereotype.Component;
import javax.naming.Context;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.util.*;

/** Public resolution for external TLS probes only; does not change system DNS or announced device addresses. */
@Component
class PublicDns {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PublicDns.class);
    List<String> resolve(String host){
        var result=new LinkedHashSet<String>();
        for(String resolver:List.of("8.8.8.8","1.1.1.1")){
            try{
                var answers=lookup(host,resolver);result.addAll(answers);
                log.info("Public DNS {} resolved {} to {}",resolver,host,answers);
            }catch(Exception e){log.warn("Public DNS {} lookup for {} failed: {}: {}",resolver,host,e.getClass().getSimpleName(),e.getMessage());}
        }
        return result.stream().limit(8).toList();
    }
    List<String> lookup(String host,String resolver) throws Exception {
        Hashtable<String,String> env=new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY,"com.sun.jndi.dns.DnsContextFactory");
        env.put(Context.PROVIDER_URL,"dns://"+resolver+"/");
        env.put("com.sun.jndi.dns.timeout.initial","1500");env.put("com.sun.jndi.dns.timeout.retries","1");
        var context=new InitialDirContext(env);
        try{
            List<String> addresses=new ArrayList<>();
            // Query separately: JNDI turns a combined A/AAAA request into ANY, which public resolvers may refuse.
            for(String type:List.of("A","AAAA")){
                try{
                    var values=context.getAttributes(host.endsWith(".")?host:host+".",new String[]{type}).get(type);
                    if(values==null)continue;
                    for(int i=0;i<values.size();i++)addresses.add(InetAddress.getByAddress(IpNetworks.literal(values.get(i).toString())).getHostAddress());
                }catch(javax.naming.NamingException e){if(addresses.isEmpty())throw e;}
            }
            return addresses;
        }finally{context.close();}
    }
}
