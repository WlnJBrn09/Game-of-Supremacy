package com.supremacy.android;

import android.os.Bundle;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.supremacy.board.AtlasApp;

public final class AndroidLauncher extends AndroidApplication {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Smoke tests (CI, adb) can start a seeded match straight away:
    //   adb shell am start -n com.supremacy.game/com.supremacy.android.AndroidLauncher --es demo autoplay
    String demo = getIntent() == null ? null : getIntent().getStringExtra("demo");
    if (demo != null) System.setProperty("supremacy.demo", demo);
    AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
    config.useImmersiveMode = true;
    config.useAccelerometer = false;
    config.useCompass = false;
    // Smooth edges on territory borders, army pieces and buttons.
    config.numSamples = 4;
    initialize(new AtlasApp(), config);
  }
}
