package org.frifra.vibedock.server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ImageQualityTest{
 @Test void calibrationRecognizesLosslessLcdAndUsesTheInternalCorridor(@TempDir Path dir)throws Exception{
  var builtins=ThemeLibrary.builtins();var item=builtins.item("handheld").orElseThrow();
  var snapshot=new ThemeLibrary.Snapshot(Map.of("handheld",item),new ThemeCatalog(List.of(item.scheme())));
  var quality=new ImageQuality(new ThemeSettings(dir.toString(),new JsonMapper()),new StatusImage("Europe/Berlin"),new Twemoji(Twemoji.locate(null).toString()));
  try{var result=quality.calibrate(snapshot,List.of(DisplayType.M1,DisplayType.of("128x160")));
   assertEquals(0,result.themes().get("handheld").worst().get(16));assertEquals(40,result.targets().get(32));
  }finally{quality.close();}
 }
}
