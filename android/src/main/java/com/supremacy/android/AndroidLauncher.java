package com.supremacy.android;

import android.os.Bundle;
import android.util.Log;
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

  /**
   * libGDX 1.13.1 crashes if the activity is destroyed before its keyboard-height helper has been
   * attached to a view (for example when Android recreates the activity right after launch): {@code
   * AndroidXKeyboardHeightProvider.close()} passes a null view to ViewCompat. By then {@code
   * AndroidApplication.onDestroy()} has already called {@code Activity.onDestroy()}, and there is
   * no listener to remove, so that one exception is safe to absorb. Anything else is rethrown.
   */
  @Override
  protected void onDestroy() {
    try {
      super.onDestroy();
    } catch (NullPointerException e) {
      if (!fromKeyboardHeightProvider(e)) throw e;
      Log.w("Supremacy", "Ignored libGDX keyboard-height cleanup on early destroy", e);
    }
  }

  private static boolean fromKeyboardHeightProvider(Throwable e) {
    for (StackTraceElement frame : e.getStackTrace())
      if (frame.getClassName().contains(".keyboardheight.")) return true;
    return false;
  }
}
