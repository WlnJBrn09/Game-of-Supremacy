package com.supremacy.android;

import android.os.Bundle;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.supremacy.board.AtlasApp;

public final class AndroidLauncher extends AndroidApplication {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
    config.useImmersiveMode = true;
    config.useAccelerometer = false;
    config.useCompass = false;
    initialize(new AtlasApp(), config);
  }
}
