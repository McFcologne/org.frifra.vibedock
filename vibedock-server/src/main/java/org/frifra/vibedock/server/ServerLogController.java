package org.frifra.vibedock.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Bounded, read-only application log tail. The administration security chain protects this endpoint. */
@RestController
class ServerLogController {
    static final int MAX_BYTES=256*1024,MAX_LINES=500;
    private final Path file;
    ServerLogController(@Value("${vibedock.log-dir}") String directory){file=Path.of(directory).resolve("vibedock.log");}
    @GetMapping(value="/admin/server-log",produces=MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> read(){
        try(var channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            long start=Math.max(0,channel.size()-MAX_BYTES);channel.position(start);
            ByteBuffer buffer=ByteBuffer.allocate(MAX_BYTES);
            while(buffer.hasRemaining()&&channel.read(buffer)>0){}
            buffer.flip();String text=StandardCharsets.UTF_8.decode(buffer).toString();
            if(start>0){int newline=text.indexOf('\n');text=newline<0?"":text.substring(newline+1);}
            var lines=text.lines().toList();
            return response(HttpStatus.OK,String.join("\n",lines.subList(Math.max(0,lines.size()-MAX_LINES),lines.size())));
        }catch(NoSuchFileException e){return response(HttpStatus.OK,"No server log available yet.");}
        catch(IOException e){return response(HttpStatus.SERVICE_UNAVAILABLE,"The server log could not be read. Check the log directory and file permissions.");}
    }
    private ResponseEntity<String> response(HttpStatus status,String body){
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text","plain",StandardCharsets.UTF_8)).body(body);
    }
}
