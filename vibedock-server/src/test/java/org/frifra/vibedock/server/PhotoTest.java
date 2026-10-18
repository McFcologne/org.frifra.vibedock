package org.frifra.vibedock.server;

import org.junit.jupiter.api.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.time.*;
import java.util.*;

/** Photo designs: the library keeps reduced upright copies, slideshows follow the clock, swipes and the time of day. */
class PhotoTest {
    static byte[] png(int w,int h,int rgb) throws Exception {
        var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)image.setRGB(x,y,x<w/2?rgb:0xffffff);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    @Test void libraryKeepsAReducedCopyAndCutsItToTheDisplay() throws Exception {
        var library=new PhotoLibrary(Files.createTempDirectory("vibedock-photos-").toString());
        var photo=library.add(png(2400,1200,0xff0000));
        Assertions.assertEquals(600,photo.width());Assertions.assertEquals(300,photo.height());
        Assertions.assertEquals(List.of(photo.id()),library.list().stream().map(PhotoLibrary.Photo::id).toList());
        var stored=ImageIO.read(new java.io.ByteArrayInputStream(library.jpeg(photo.id()).orElseThrow()));
        Assertions.assertEquals(600,stored.getWidth());
        // Cut to 172 x 320 from the middle: the left half is red, the right half white.
        var image=library.render(photo.id(),DisplayType.of("172x320")).orElseThrow();
        Assertions.assertEquals(172*320*2,image.rgb565().length);
        var shown=ImageIO.read(new java.io.ByteArrayInputStream(image.png()));
        Assertions.assertEquals(172,shown.getWidth());Assertions.assertEquals(320,shown.getHeight());
        Assertions.assertTrue((shown.getRGB(10,160)&0xffffff)>0xe00000&&(shown.getRGB(10,160)&0xff)<0x30,"red on the left");
        Assertions.assertEquals(0xffffff,shown.getRGB(160,160)&0xf8fcf8|0x070307);
        Assertions.assertThrows(IllegalArgumentException.class,()->library.add("not a photo".getBytes()));
        Assertions.assertTrue(library.delete(photo.id()));Assertions.assertTrue(library.list().isEmpty());
        Assertions.assertTrue(library.render(photo.id(),DisplayType.M1).isEmpty());
    }
    @Test void exifOrientationTurnsPhonePhotosUpright(){
        var wide=new BufferedImage(40,20,BufferedImage.TYPE_INT_RGB);wide.setRGB(0,0,0xff0000);
        var turned=PhotoLibrary.upright(wide,6);  // 90 degrees clockwise
        Assertions.assertEquals(20,turned.getWidth());Assertions.assertEquals(40,turned.getHeight());
        Assertions.assertEquals(0xff0000,turned.getRGB(19,0)&0xffffff);
        Assertions.assertEquals(0xff0000,PhotoLibrary.upright(wide,8).getRGB(0,39)&0xffffff);
        Assertions.assertEquals(0xff0000,PhotoLibrary.upright(wide,3).getRGB(39,19)&0xffffff);
        // A minimal JPEG start with an EXIF block (big-endian) whose orientation is 6.
        byte[] jpeg={(byte)0xFF,(byte)0xD8,(byte)0xFF,(byte)0xE1,0,34,'E','x','i','f',0,0,'M','M',0,42,0,0,0,8,0,1,0x01,0x12,0,3,0,0,0,1,0,6,0,0,0,0,0,0,0,0};
        Assertions.assertEquals(6,PhotoLibrary.exifOrientation(jpeg));
        Assertions.assertEquals(1,PhotoLibrary.exifOrientation(new byte[]{1,2,3}));
    }
    @Test void slideshowsFollowTheClockSwipesAndTheTimeOfDay() throws Exception {
        var library=new PhotoLibrary(Files.createTempDirectory("vibedock-photos-").toString());
        var a=library.add(png(100,100,0xff0000)).id();var b=library.add(png(100,100,0x00ff00)).id();var c=library.add(png(100,100,0x0000ff)).id();
        var clock=new MutableClock(Instant.parse("2026-10-18T12:00:00Z"));
        var designs=new DesignService(null,null,null,null,library,clock);
        var photos=new Design.Photos(List.of(a,b,c),60,false,null,null);
        var design=Design.DEFAULT.withLayout(Layout.DEFAULT);design=new Design(design.scheme(),design.mood(),null,design.layout(),photos);
        String first=designs.currentPhoto(design,"d1").orElseThrow();
        clock.now=clock.now.plusSeconds(60);String second=designs.currentPhoto(design,"d1").orElseThrow();
        Assertions.assertNotEquals(first,second);
        Assertions.assertEquals(List.of(a,b,c).get((List.of(a,b,c).indexOf(first)+1)%3),second);
        // A swipe moves only this display on; another display keeps the clock's photo.
        Assertions.assertTrue(designs.skip(design,"d1",1));
        Assertions.assertNotEquals(second,designs.currentPhoto(design,"d1").orElseThrow());
        Assertions.assertEquals(second,designs.currentPhoto(design,"d2").orElseThrow());
        Assertions.assertEquals(30,designs.pollSeconds(design,30));
        Assertions.assertEquals(60,designs.pollSeconds(design,300));
        // Shuffled: every photo once per round.
        var shuffled=new Design(design.scheme(),design.mood(),null,design.layout(),new Design.Photos(List.of(a,b,c),10,true,null,null));
        var round=new HashSet<String>();clock.now=Instant.ofEpochSecond(3000);
        for(int i=0;i<3;i++){round.add(designs.currentPhoto(shuffled,"x").orElseThrow());clock.now=clock.now.plusSeconds(10);}
        Assertions.assertEquals(Set.of(a,b,c),round);
        // Only at night: the status at noon (no photo, no swipe), the photos at 23:00 server time.
        var night=new Design(design.scheme(),design.mood(),null,design.layout(),new Design.Photos(List.of(a,b,c),60,false,"22:00","07:00"));
        clock.now=LocalDate.of(2026,10,18).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant();
        Assertions.assertTrue(designs.currentPhoto(night,"d1").isEmpty());Assertions.assertFalse(designs.skip(night,"d1",1));
        Assertions.assertEquals(300,designs.pollSeconds(night,300));
        clock.now=LocalDate.of(2026,10,18).atTime(23,0).atZone(ZoneId.systemDefault()).toInstant();
        Assertions.assertTrue(designs.currentPhoto(night,"d1").isPresent());
        // Paused (the usage monitor chosen): no photo, the selection kept.
        var paused=new Design(design.scheme(),design.mood(),null,design.layout(),new Design.Photos(List.of(a,b,c),60,false,null,null,true));
        Assertions.assertTrue(designs.currentPhoto(paused,"d1").isEmpty());Assertions.assertEquals(3,paused.photos().images().size());
        Assertions.assertEquals(300,designs.pollSeconds(paused,300));
        // A double tap: on at noon despite the night schedule, off at night; for that display only.
        clock.now=LocalDate.of(2026,10,18).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant();
        Assertions.assertTrue(designs.togglePhotos(night,"d1"));
        Assertions.assertTrue(designs.currentPhoto(night,"d1").isPresent());Assertions.assertTrue(designs.currentPhoto(night,"d2").isEmpty());
        Assertions.assertEquals(60,designs.pollSeconds(night,300,"d1"));
        clock.now=LocalDate.of(2026,10,18).atTime(23,0).atZone(ZoneId.systemDefault()).toInstant();
        Assertions.assertTrue(designs.togglePhotos(night,"d1"));
        Assertions.assertTrue(designs.currentPhoto(night,"d1").isEmpty());Assertions.assertTrue(designs.currentPhoto(night,"d2").isPresent());
        Assertions.assertFalse(designs.skip(night,"d1",1),"no swipe through photos that are off");
        // Also when the editor chose the usage monitor; nothing without photos.
        var paused2=new Design(design.scheme(),design.mood(),null,design.layout(),new Design.Photos(List.of(a,b,c),60,false,null,null,true));
        Assertions.assertTrue(designs.togglePhotos(paused2,"d3"));Assertions.assertTrue(designs.currentPhoto(paused2,"d3").isPresent());
        Assertions.assertFalse(designs.togglePhotos(Design.DEFAULT,"d4"));
        // A removed photo is skipped.
        library.delete(a);library.delete(b);library.delete(c);
        Assertions.assertTrue(designs.currentPhoto(design,"d1").isEmpty());
    }
    @Test void photoSettingsAreValidated(){
        Assertions.assertTrue(new Design.Photos(List.of(),30,false,"22:00","07:00").active(LocalTime.of(23,0))==false);
        var night=new Design.Photos(List.of("0123456789abcdef"),30,false,"22:00","07:00");
        Assertions.assertTrue(night.active(LocalTime.of(23,0)));Assertions.assertTrue(night.active(LocalTime.of(6,59)));
        Assertions.assertFalse(night.active(LocalTime.of(7,0)));Assertions.assertFalse(night.active(LocalTime.of(12,0)));
        var day=new Design.Photos(List.of("0123456789abcdef"),30,false,"08:00","18:00");
        Assertions.assertTrue(day.active(LocalTime.of(8,0)));Assertions.assertFalse(day.active(LocalTime.of(18,0)));
        Assertions.assertThrows(IllegalArgumentException.class,()->new Design.Photos(List.of("../etc"),30,false,null,null));
        Assertions.assertThrows(IllegalArgumentException.class,()->new Design.Photos(List.of(),5,false,null,null));
        Assertions.assertThrows(IllegalArgumentException.class,()->new Design.Photos(List.of(),30,false,"22:00",null));
        Assertions.assertThrows(IllegalArgumentException.class,()->new Design.Photos(List.of(),30,false,"25:00","07:00"));
    }
    static final class MutableClock extends Clock {
        Instant now;MutableClock(Instant now){this.now=now;}
        @Override public ZoneId getZone(){return ZoneId.systemDefault();}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
