package org.frifra.vibedock.server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ImageQualityTest{
 @Test void referenceScenesCoverLowAverageAndHighQuota(){
  assertEquals(5,ImageQuality.referenceRemaining(0));assertEquals(50,ImageQuality.referenceRemaining(1));assertEquals(95,ImageQuality.referenceRemaining(2));
  assertEquals(95,ImageQuality.reference(0).providers().get("codex").fiveHour().usedPercent());
  assertEquals(50,ImageQuality.reference(1).providers().get("codex").fiveHour().usedPercent());
  assertEquals(5,ImageQuality.reference(2).providers().get("codex").fiveHour().usedPercent());
 }
 @Test void calibrationRecognizesLosslessLcdAndUsesTheInternalCorridor(@TempDir Path dir)throws Exception{
  var builtins=ThemeLibrary.builtins();var item=builtins.item("handheld").orElseThrow();
  var snapshot=new ThemeLibrary.Snapshot(Map.of("handheld",item),new ThemeCatalog(List.of(item.scheme())));
  var quality=new ImageQuality(new ThemeSettings(dir.toString(),new JsonMapper()),new StatusImage("Europe/Berlin"),new Twemoji(Twemoji.locate(null).toString()));
  try{var result=quality.calibrate(snapshot,List.of(DisplayType.M1,DisplayType.of("128x160")));
   assertEquals(0,result.themes().get("handheld").worst().get(16));assertEquals(40,result.targets().get(32));
  }finally{quality.close();}
 }
}
