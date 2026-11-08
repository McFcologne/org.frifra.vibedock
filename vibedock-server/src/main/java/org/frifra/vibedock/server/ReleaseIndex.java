package org.frifra.vibedock.server;

import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The {@code index.json} of a client release (schema 1, written by {@code vibedock-client/scripts/release-index.py}):
 * the client version, the protocol version of the firmware and, per board, the three files with SHA-256 and size.
 * Parsed strictly: whatever is not as expected is an error naming it.
 */
record ReleaseIndex(String client,int protocol,List<Board> boards) {
    static final int SCHEMA=1,MAX_BYTES=256*1024;
    private static final Pattern VERSION=Pattern.compile("[0-9A-Za-z.+-]{1,40}"),ID=Pattern.compile("[a-z0-9-]{1,40}"),
            HEX=Pattern.compile("[0-9a-f]{64}"),FILE=Pattern.compile("[A-Za-z0-9._+-]{1,120}");
    private static final tools.jackson.databind.ObjectMapper JSON=JsonCompatibility.mapper();
    record File(String file,String sha256,long size) {}
    record Board(String id,File flash,File app,File manifest) {}

    static ReleaseIndex parse(byte[] bytes){
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new IllegalArgumentException("index.json is empty or larger than "+MAX_BYTES/1024+" KB");
        JsonNode root;
        try{root=JSON.readTree(bytes);}catch(RuntimeException e){throw new IllegalArgumentException("index.json is not valid JSON");}
        if(root==null||!root.isObject())throw new IllegalArgumentException("index.json is not an object");
        if(!root.path("schema").isInt()||root.path("schema").asInt()!=SCHEMA)throw new IllegalArgumentException("index.json: schema "+SCHEMA+" expected");
        String client=text(root,"client",VERSION);
        if(!root.path("protocol").isInt()||root.path("protocol").asInt()<1)throw new IllegalArgumentException("index.json: protocol is a number");
        var list=root.path("boards");
        if(!list.isArray()||list.isEmpty()||list.size()>64)throw new IllegalArgumentException("index.json: boards is a list of 1 to 64");
        var boards=new ArrayList<Board>();var ids=new HashSet<String>();
        for(var b:list){
            String id=text(b,"id",ID);
            if(!ids.add(id))throw new IllegalArgumentException("index.json: board "+id+" twice");
            boards.add(new Board(id,file(b,"flash"),file(b,"app"),file(b,"manifest")));
        }
        return new ReleaseIndex(client,root.path("protocol").asInt(),List.copyOf(boards));
    }
    private static String text(JsonNode n,String key,Pattern form){
        var v=n.path(key);
        if(!v.isString()||!form.matcher(v.asString()).matches())throw new IllegalArgumentException("index.json: "+key+" is missing or not valid");
        return v.asString();
    }
    private static File file(JsonNode board,String key){
        var n=board.path(key);
        if(!n.isObject())throw new IllegalArgumentException("index.json: "+key+" of "+board.path("id").asString()+" is missing");
        long size=n.path("size").asLong(-1);
        if(!n.path("size").isIntegralNumber()||size<1||size>Firmware.MAX_SIZE)throw new IllegalArgumentException("index.json: size of "+key+" is not valid");
        return new File(text(n,"file",FILE),text(n,"sha256",HEX),size);
    }
    Optional<Board> board(String id){return boards.stream().filter(b->b.id().equals(id)).findFirst();}
}
