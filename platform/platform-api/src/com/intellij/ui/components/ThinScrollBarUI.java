// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.components;

import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

@ApiStatus.Internal
public class ThinScrollBarUI extends DefaultScrollBarUI {
  private final static int DEFAULT_THICKNESS = 3;
  ThinScrollBarUI() {
    super(DEFAULT_THICKNESS, DEFAULT_THICKNESS, DEFAULT_THICKNESS);
  }

  public ThinScrollBarUI(int thickness, int thicknessMax, int thicknessMin) {
    super(thickness, thicknessMax, thicknessMin);
  }

  @Override
  protected ScrollBarPainter.Thumb createThumbPainter() {
    return new ScrollBarPainter.ThinScrollBarThumb(() -> myScrollBar, false);
  }

  @Override
  void paintTrack(Graphics2D g, JComponent c) {
    // Track is not needed
  }

  @Override
  void paintThumb(Graphics2D g, JComponent c) {
    if (isOpaque(c)) {
      paint(myThumb, g, c, ScrollSettings.isThumbSmallIfOpaque.invoke());
    }
    else if (myAnimationBehavior != null && myAnimationBehavior.getThumbFrame() > 0) {
      paint(myThumb, g, c, false);
    }
  }

  @Override
  protected @NotNull Insets getInsets(boolean small) {
    return JBUI.emptyInsets();
  }
}