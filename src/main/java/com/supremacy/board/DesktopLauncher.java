package com.supremacy.board;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

public final class DesktopLauncher {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        String dataDir=System.getProperty("supremacy.saveDir",System.getProperty("user.home")+"/.supremacy");
        System.setProperty("supremacy.saveDir",dataDir);
        config.setPreferencesConfig(dataDir,com.badlogic.gdx.Files.FileType.Absolute);
        config.setTitle("Supremacy | Offline Conquest");
        config.setWindowedMode(1600,1000);
        config.setWindowSizeLimits(960,600,-1,-1);
        config.useVsync(true);
        config.disableAudio(Boolean.getBoolean("supremacy.noAudio"));
        config.setForegroundFPS(60);
        config.setBackBufferConfig(8,8,8,8,16,0,4);
        new Lwjgl3Application(new AtlasApp(),config);
    }
}
