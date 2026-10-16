package org.frifra.vibedock.server;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import java.nio.file.*;
import java.util.Optional;

/** Build metadata shared by all administration pages. */
@ControllerAdvice
class ApplicationInfo {
    private final BuildProperties build;
    private final Path manual;
    ApplicationInfo(ObjectProvider<BuildProperties> build,@Value("${vibedock.manual-file}") String manual){
        this.build=build.getIfAvailable();this.manual=Path.of(manual);
    }
    @ModelAttribute("appVersion") public String getVersion(){return build==null?"development":version(build.getVersion(),build.get("git.describe"));}
    /**
     * The version passed to the build (the Docker build's VIBEDOCK_VERSION from the Git tag); without it, the Git
     * description of the checkout it was built from, in the form of scripts/version.sh.
     */
    static String version(String built,String describe){
        if(built!=null&&!built.endsWith("-SNAPSHOT"))return built;
        if(describe==null||describe.isBlank()||describe.equals("none")||describe.startsWith("${"))return built;
        if(describe.matches("[0-9a-f]{7,40}(-dirty)?"))return "0.0.0-g"+describe;
        return describe.startsWith("v")?describe.substring(1):describe;
    }
    /** The user manual as PDF, if this installation has one (the Docker image does; a local Maven run does not). */
    Optional<Path> manual(){return Files.isRegularFile(manual)?Optional.of(manual):Optional.empty();}
    @ModelAttribute("manualAvailable") public boolean getManualAvailable(){return manual().isPresent();}
    @ModelAttribute("readmeUrl") public String getReadmeUrl(){return "https://github.com/McFcologne/org.frifra.vibedock#readme";}
}
