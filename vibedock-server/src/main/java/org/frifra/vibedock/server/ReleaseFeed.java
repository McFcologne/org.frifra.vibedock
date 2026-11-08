package org.frifra.vibedock.server;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Where the releases of a firmware source are listed and their files fetched from (GitHub; a test supplies its own). */
interface ReleaseFeed {
    /** assets: file name to download address. */
    record Release(String tag,String version,Map<String,String> assets) {}
    /** The published (no draft, no pre-release) releases whose tag starts with the source's prefix, newest first. */
    List<Release> releases(FirmwareSources.Source source) throws IOException;
    /** A file of a release, at most maxBytes long; anything longer is an error. */
    byte[] fetch(String url,int maxBytes) throws IOException;
}
