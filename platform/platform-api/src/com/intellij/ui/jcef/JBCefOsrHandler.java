// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.jcef;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.util.registry.RegistryManager;
import com.intellij.ui.Gray;
import com.intellij.util.JBHiDPIScaledImage;
import com.intellij.util.RetinaImage;
import com.intellij.util.ui.UIUtil;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;
import org.cef.misc.CefRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.VolatileImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static com.intellij.ui.paint.PaintUtil.RoundingMode.CEIL;
import static com.intellij.ui.paint.PaintUtil.RoundingMode.ROUND;

/**
 * A render handler for an off-screen browser.
 *
 * @author tav
 * @see JBCefOsrComponent
 */
class JBCefOsrHandler implements CefRenderHandler {
  private final @NotNull AtomicReference<Point> myLocationOnScreenRef = new AtomicReference<>(new Point());
  private final @NotNull JBCefFpsMeter myFpsMeter = JBCefFpsMeter.register(
    RegistryManager.getInstance().get("ide.browser.jcef.osr.measureFPS.id").asString());

  protected volatile @Nullable JBHiDPIScaledImage myImage;

  private volatile @Nullable JBHiDPIScaledImage myPopupImage;
  private volatile boolean myPopupShown = false;
  private volatile @NotNull Rectangle myPopupBounds = new Rectangle();
  private final Object myPopupMutex = new Object();

  private volatile @Nullable VolatileImage myVolatileImage;
  protected volatile boolean myContentOutdated = false;

  private volatile @Nullable JBCefCaretListener myCaretListener;

  // DIP(device independent pixel aka logic pixel) size in screen pixels. Expected to be != 1 only if JRE supports HDPI
  private volatile double myPixelDensity = 1;
  private volatile double myScaleFactor = 1;

  public void setScreenInfo(double pixelDensity, double scaleFactor) {
    myPixelDensity = pixelDensity;
    myScaleFactor = scaleFactor;
  }

  protected double getPixelDensity() { return myPixelDensity; }

  protected double getScaleFactor() { return myScaleFactor; }

  @Override
  public Rectangle getViewRect(CefBrowser browser) {
    Component component = browser.getUIComponent();
    double scale = getScaleFactor();
    return new Rectangle(0, 0, CEIL.round(component.getWidth() / scale), CEIL.round(component.getHeight() / scale));
  }

  @Override
  public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
    Rectangle rect = getScreenBoundaries(browser.getUIComponent());
    double scale = myScaleFactor * myPixelDensity;
    screenInfo.Set(scale, 32, 4, false, rect, rect);
    return true;
  }

  private static Rectangle getScreenBoundaries(Component component) {
    if (component != null && !GraphicsEnvironment.isHeadless()) {
      try {
        return component.isShowing() ?
               component.getGraphicsConfiguration().getDevice().getDefaultConfiguration().getBounds() :
               GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
      }
      catch (Exception e) {
        Logger.getInstance(JBCefOsrHandler.class).error(e);
      }
    }

    return new Rectangle(0, 0, 0, 0);
  }

  @Override
  public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
    Point pt = viewPoint.getLocation();
    Point loc = myLocationOnScreenRef.get();
    if (SystemInfoRt.isMac) {
      Rectangle rect = getScreenBoundaries(browser.getUIComponent());
      pt.setLocation(loc.x + pt.x, rect.height - loc.y - pt.y);
    }
    else {
      pt.translate(loc.x, loc.y);
    }
    return SystemInfoRt.isWindows ? toRealCoordinates(pt) : pt;
  }

  @Override
  public double getDeviceScaleFactor(CefBrowser browser) {
    return myScaleFactor * myPixelDensity;
  }

  @Override
  public void onPopupShow(CefBrowser browser, boolean show) {
    synchronized (myPopupMutex) {
      myPopupShown = show;
    }
  }

  @Override
  public void onPopupSize(CefBrowser browser, Rectangle size) {
    synchronized (myPopupMutex) {
      myPopupBounds = size;
    }
  }

  @Override
  public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
    myFpsMeter.onPaintStarted();
    JBHiDPIScaledImage image = popup ? myPopupImage : myImage;

    Dimension size = getRealImageSize(image);
    if (size.width != width || size.height != height) {
      image = (JBHiDPIScaledImage)RetinaImage.createFrom(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE),
                                                         getPixelDensity(), null);
    }

    assert image != null;
    if (popup) {
      synchronized (myPopupMutex) {
        drawByteBuffer(image, buffer, dirtyRects);
        myPopupImage = image;
      }
    }
    else {
      drawByteBuffer(image, buffer, dirtyRects);
      myImage = image;
    }

    myContentOutdated = true;
    SwingUtilities.invokeLater(() -> {
      if (!browser.getUIComponent().isShowing()) return;
      Component component = browser.getUIComponent();
      JRootPane root = SwingUtilities.getRootPane(component);
      RepaintManager rm = RepaintManager.currentManager(root);
      Rectangle dirtySrc = new Rectangle(0, 0, component.getWidth(), component.getHeight());
      Rectangle dirtyDst = SwingUtilities.convertRectangle(component, dirtySrc, root);
      int dx = 1;
      // NOTE: should mark area outside browser (otherwise background component won't be repainted)
      rm.addDirtyRegion(root, dirtyDst.x - dx, dirtyDst.y - dx, dirtyDst.width + dx * 2, dirtyDst.height + dx * 2);
    });

    { // notify fps-meter
      long pixCount = 0;
      for (Rectangle r : dirtyRects)
        pixCount += (long)r.width * r.height;
      myFpsMeter.onPaintFinished(pixCount);
    }
  }

  @Override
  public boolean onCursorChange(CefBrowser browser, int cursorType) {
    SwingUtilities.invokeLater(() -> browser.getUIComponent().setCursor(new Cursor(cursorType)));
    return true;
  }

  @Override
  public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
    return false;
  }

  @Override
  public void updateDragCursor(CefBrowser browser, int operation) {
  }

  @Override
  public void OnImeCompositionRangeChanged(CefBrowser browser, CefRange selectionRange, Rectangle[] characterBounds) {
    JBCefCaretListener listener = myCaretListener;
    if (listener != null) {
      listener.onImeCompositionRangeChanged(selectionRange, characterBounds);
    }
  }

  @Override
  public void OnTextSelectionChanged(CefBrowser browser, String selectedText, CefRange selectionRange) {
    JBCefCaretListener listener = myCaretListener;
    if (listener != null) {
      listener.onTextSelectionChanged(selectedText, selectionRange);
    }
  }

  public void paint(Graphics2D g) {
    JBHiDPIScaledImage image = myImage;
    if (image == null) {
      return;
    }

    myFpsMeter.paintFrameStarted();
    VolatileImage vi = myVolatileImage;

    do {
      boolean contentOutdated = myContentOutdated;
      myContentOutdated = false;
      if (vi == null || vi.getWidth() != image.getWidth() || vi.getHeight() != image.getHeight()) {
        vi = createVolatileImage(g, image.getWidth(), image.getHeight());
      }
      else if (contentOutdated) {
        drawVolatileImage(vi);
      }

      switch (vi.validate(g.getDeviceConfiguration())) {
        case VolatileImage.IMAGE_RESTORED -> drawVolatileImage(vi);
        case VolatileImage.IMAGE_INCOMPATIBLE -> vi = createVolatileImage(g, image.getWidth(), image.getHeight());
      }

      g.drawImage(vi, 0, 0, null);
    }
    while (vi.contentsLost());

    myVolatileImage = vi;

    myFpsMeter.paintFrameFinished(g);
  }

  public void setLocationOnScreen(Point location) {
    myLocationOnScreenRef.set(location);
  }

  private static @NotNull Dimension getRealImageSize(JBHiDPIScaledImage image) {
    if (image == null) return new Dimension(0, 0);
    BufferedImage bi = (BufferedImage)image.getDelegate();
    assert bi != null;
    return new Dimension(bi.getWidth(), bi.getHeight());
  }

  private @NotNull Point toRealCoordinates(@NotNull Point pt) {
    double scale = getPixelDensity();
    return new Point(ROUND.round(pt.x * scale), ROUND.round(pt.y * scale));
  }

  private static void drawByteBuffer(@NotNull JBHiDPIScaledImage dst, @NotNull ByteBuffer src, Rectangle[] rectangles) {
    BufferedImage image = (BufferedImage)dst.getDelegate();
    assert image != null;
    int[] dstData = ((DataBufferInt)image.getRaster().getDataBuffer()).getData();
    IntBuffer srcData = src.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
    for (Rectangle rect : rectangles) {
      if (rect.width < image.getWidth()) {
        for (int line = rect.y; line < rect.y + rect.height; line++) {
          int offset = line * image.getWidth() + rect.x;
          srcData.position(offset).get(dstData, offset, Math.min(rect.width, src.capacity() - offset));
        }
      }
      else { // optimized for a buffer wide dirty rect
        int offset = rect.y * image.getWidth();
        srcData.position(offset).get(dstData, offset, Math.min(rect.height * image.getWidth(), src.capacity() - offset));
      }
    }
  }

  protected void drawVolatileImage(VolatileImage vi) {
    JBHiDPIScaledImage image = myImage;

    // Draw buffered image into VolatileImage
    Graphics2D g = (Graphics2D)vi.getGraphics().create();
    try {
      g.setBackground(Gray.TRANSPARENT);
      g.setComposite(AlphaComposite.Src);
      g.clearRect(0, 0, vi.getWidth(), vi.getHeight());

      // Draw volatile image from BufferedImage
      if (image != null) {
        UIUtil.drawImage(g, image, 0, 0, null);
      }

      if (myPopupShown) {
        synchronized (myPopupMutex) {
          Image popupImage = myPopupImage;
          if (myPopupShown && popupImage != null) {
            UIUtil.drawImage(g, popupImage, myPopupBounds.x, myPopupBounds.y, null);
          }
        }
      }
    }
    finally {
      g.dispose();
    }
  }

  private VolatileImage createVolatileImage(Graphics2D g, int width, int height) {
    VolatileImage image = g.getDeviceConfiguration().createCompatibleVolatileImage(width, height, Transparency.TRANSLUCENT);
    drawVolatileImage(image);
    return image;
  }

  void addCaretListener(JBCefCaretListener listener) {
    myCaretListener = listener;
  }
}
