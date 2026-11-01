package org.frifra.vibedock.server;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
class LosslessPngTest{
 @Test void palettePngHasExactlyTheSamePixelsAndNeverGetsBigger()throws Exception{
  for(int colors:new int[]{1,4,64,256,512}){
   var image=new BufferedImage(240,240,BufferedImage.TYPE_INT_RGB);
   for(int y=0;y<240;y++)for(int x=0;x<240;x++)image.setRGB(x,y,((x+240*y)%colors)*456791);
   var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
   byte[] packed=LosslessPng.encode(image);assertTrue(packed.length<=bytes.size());
   var decoded=ImageIO.read(new ByteArrayInputStream(packed));
   assertArrayEquals(image.getRGB(0,0,240,240,null,0,240),decoded.getRGB(0,0,240,240,null,0,240));
  }
 }
}
