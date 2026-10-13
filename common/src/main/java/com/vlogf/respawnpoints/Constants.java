package com.vlogf.respawnpoints;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {
    public static final String MOD_ID = "respawnpoints";
    public static final String MOD_NAME = "Respawn Points";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    /** Build timestamp from the jar manifest ("dev" in dev runs). Identifies the exact build in logs. */
    public static String buildTime() {
        try {
            String path = Constants.class.getResource("Constants.class").toString();
            int bang = path.indexOf('!');
            if (bang < 0) {
                return "dev";
            }
            String jarPath = path.substring("jar:file:".length(), bang);
            try (var jar = new java.util.jar.JarFile(java.net.URLDecoder.decode(jarPath, "UTF-8"))) {
                String builtAt = jar.getManifest().getMainAttributes().getValue("Build-Time");
                return builtAt != null ? builtAt : "dev";
            }
        } catch (Exception e) {
            return "dev";
        }
    }
}
