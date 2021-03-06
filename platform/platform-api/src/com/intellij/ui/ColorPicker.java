/*
 * Copyright 2000-2011 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.ui;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.Alarm;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ImageObserver;
import java.awt.image.MemoryImageSource;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author pegov
 * @author Konstantin Bulenkov
 */
public class ColorPicker extends JPanel implements ColorListener, DocumentListener {
  public static final Icon PICK = IconLoader.findIcon("/ide/pipette.png");
  private static final String COLOR_CHOOSER_COLORS_KEY = "ColorChooser.RecentColors";

  private Color myColor;
  private ColorPreviewComponent myPreviewComponent;
  private final ColorWheelPanel myColorWheelPanel;
  private final JTextField myRed;
  private final JTextField myGreen;
  private final JTextField myBlue;
  private final JTextField myHex;
  private final Alarm myUpdateQueue;

  private RecentColorsComponent myRecentColorsComponent;
  private final ColorPipette myPicker;

  private ColorPicker(@NotNull Disposable parent, @Nullable Color color, boolean enableOpacity) {
    this(parent, color, true, enableOpacity);
  }

  private ColorPicker(Disposable parent, @Nullable Color color, boolean restoreColors, boolean enableOpacity) {
    myUpdateQueue = new Alarm(Alarm.ThreadToUse.SWING_THREAD, parent);
    myRed = createColorField(false);
    myGreen = createColorField(false);
    myBlue = createColorField(false);
    myHex = createColorField(true);
    setLayout(new BorderLayout());
    setBorder(BorderFactory.createEmptyBorder(5, 5, 0, 5));

    myColorWheelPanel = new ColorWheelPanel(this, enableOpacity);
    myPicker = new ColorPipette(this, getColor());
    myPicker.setListener(new ColorListener() {
      @Override
      public void colorChanged(Color color, Object source) {
        setColor(color, source);
      }
    });
    try {
      add(buildTopPanel(true), BorderLayout.NORTH);
      add(myColorWheelPanel, BorderLayout.CENTER);

      myRecentColorsComponent = new RecentColorsComponent(new ColorListener() {
        @Override
        public void colorChanged(Color color, Object source) {
          setColor(color, source);
        }
      }, restoreColors);

      add(myRecentColorsComponent, BorderLayout.SOUTH);
    }
    catch (ParseException ignore) {
    }

    Color c = color == null ? myRecentColorsComponent.getMostRecentColor() : color;
    if (c == null) {
      c = Color.WHITE;
    }
    setColor(c, this);

    setSize(300, 350);
  }

  private JTextField createColorField(boolean hex) {
    final NumberDocument doc = new NumberDocument(hex);
    final JTextField filed = new JTextField(doc, "", hex ? 6 : 3);
    doc.setSource(filed);
    filed.getDocument().addDocumentListener(this);
    filed.addFocusListener(new FocusAdapter() {
      @Override
      public void focusGained(final FocusEvent e) {
        filed.selectAll();
      }
    });
    return filed;
  }

  public JComponent getPreferredFocusedComponent() {
    return myHex;
  }

  private void setColor(Color color, Object src) {
    colorChanged(color, src);
    myColorWheelPanel.setColor(color, src);
  }

  public void appendRecentColor() {
    myRecentColorsComponent.appendColor(myColor);
  }

  public void saveRecentColors() {
    myRecentColorsComponent.saveColors();
  }

  public Color getColor() {
    return myColor;
  }

  @Override
  public void insertUpdate(DocumentEvent e) {
    update(((NumberDocument)e.getDocument()).mySrc);
  }

  private void update(final JTextField src) {
    myUpdateQueue.cancelAllRequests();
    myUpdateQueue.addRequest(new Runnable() {
      @Override
      public void run() {
        validateAndUpdatePreview(src);
      }
    }, 300);
  }

  @Override
  public void removeUpdate(DocumentEvent e) {
    update(((NumberDocument)e.getDocument()).mySrc);
  }

  @Override
  public void changedUpdate(DocumentEvent e) {
    // ignore
  }

  private void validateAndUpdatePreview(JTextField src) {
    final Color color;
    if (myHex.hasFocus()) {
      Color c = ColorUtil.fromHex(myHex.getText(), null);
      color = c != null ? new Color(c.getRed(), c.getGreen(), c.getBlue(), (int)(255 * myColorWheelPanel.myColorWheel.myOpacity)) : null;
    } else {
      color = gatherRGB();
    }
    if (color != null) {
      updatePreview(color, src == myHex);
    }
  }

  private void updatePreview(Color color, boolean fromHex) {
    if (color != null && !color.equals(myColor)) {
      myColor = color;
      myPreviewComponent.setColor(color);
      myColorWheelPanel.setColor(color, fromHex ? myHex : null);


      if (fromHex) {
        applyColorToRGB(color);
      }
      else {
        applyColorToHEX(color);
      }
    }
  }

  @Override
  public void colorChanged(Color color, Object source) {
    if (color != null && !color.equals(myColor)) {
      myColor = color;
      applyColorToRGB(color);
      if (source != myHex) {
        applyColorToHEX(color);
      }
      myPreviewComponent.setColor(color);
    }
  }

  @Nullable
  private Color gatherRGB() {
    try {
      return new Color(Integer.parseInt(myRed.getText()),
                       Integer.parseInt(myGreen.getText()),
                       Integer.parseInt(myBlue.getText()));
    } catch (Exception ignore) {      
    }
    return null;
  }

  private void applyColorToHEX(final Color c) {
    myHex.setText(String.format("%06X", (0xFFFFFF & c.getRGB())));
  }

  private void applyColorToRGB(final Color color) {
    myRed.setText(String.valueOf(color.getRed()));
    myGreen.setText(String.valueOf(color.getGreen()));
    myBlue.setText(String.valueOf(color.getBlue()));
  }

  @Nullable
  public static Color showDialog(Component parent, String caption, Color preselectedColor, boolean enableOpacity) {
    final ColorPickerDialog dialog = new ColorPickerDialog(parent, caption, preselectedColor, enableOpacity);
    dialog.show();
    if (dialog.getExitCode() == DialogWrapper.OK_EXIT_CODE) {
      return dialog.getColor();
    }

    return null;
  }

  private JComponent buildTopPanel(boolean enablePipette) throws ParseException {
    final JPanel result = new JPanel(new BorderLayout());

    final JPanel previewPanel = new JPanel(new BorderLayout());
    if (enablePipette && ColorPipette.isAvailable()) {
      final JButton pipette = new JButton(PICK);
      pipette.setFocusable(false);
      pipette.addActionListener(new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
          myPicker.myOldColor = getColor();
          myPicker.pick();
          //JBPopupFactory.getInstance().createBalloonBuilder(new JLabel("Press ESC button to close pipette"))
          //  .setAnimationCycle(2000)
          //  .setSmallVariant(true)
          //  .createBalloon().show(new RelativePoint(pipette, new Point(pipette.getWidth() / 2, 0)), Balloon.Position.above);
        }
      });
      previewPanel.add(pipette, BorderLayout.WEST);
    }

    myPreviewComponent = new ColorPreviewComponent();
    previewPanel.add(myPreviewComponent, BorderLayout.CENTER);

    result.add(previewPanel, BorderLayout.NORTH);

    final JPanel rgbPanel = new JPanel();
    rgbPanel.setLayout(new BoxLayout(rgbPanel, BoxLayout.X_AXIS));
    rgbPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
    rgbPanel.add(new JLabel("R:"));
    rgbPanel.add(Box.createHorizontalStrut(2));
    rgbPanel.add(myRed);
    rgbPanel.add(Box.createHorizontalStrut(5));
    rgbPanel.add(new JLabel("G:"));
    rgbPanel.add(Box.createHorizontalStrut(2));
    rgbPanel.add(myGreen);
    rgbPanel.add(Box.createHorizontalStrut(5));
    rgbPanel.add(new JLabel("B:"));
    rgbPanel.add(Box.createHorizontalStrut(2));
    rgbPanel.add(myBlue);

    result.add(rgbPanel, BorderLayout.WEST);

    final JPanel hexPanel = new JPanel();
    hexPanel.setLayout(new BoxLayout(hexPanel, BoxLayout.X_AXIS));
    hexPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
    hexPanel.add(new JLabel("#:"));
    hexPanel.add(Box.createHorizontalStrut(3));
    hexPanel.add(myHex);

    result.add(hexPanel, BorderLayout.EAST);

    return result;
  }

  private static class ColorWheelPanel extends JPanel {
    private ColorWheel myColorWheel;
    private SlideComponent myBrightnessComponent;
    private SlideComponent myOpacityComponent = null;

    private ColorWheelPanel(ColorListener listener, boolean enableOpacity) {
      setLayout(new BorderLayout());
      setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

      myColorWheel = new ColorWheel();
      add(myColorWheel, BorderLayout.CENTER);

      myColorWheel.addListener(listener);

      myBrightnessComponent = new SlideComponent(true);
      myBrightnessComponent.setToolTipText("Brightness");
      myBrightnessComponent.addListener(new Consumer<Integer>() {
        @Override
        public void consume(Integer value) {
          myColorWheel.setBrightness(1f - (value / 100f));
          myColorWheel.repaint();
        }
      });

      add(myBrightnessComponent, BorderLayout.EAST);

      if (enableOpacity) {
        myOpacityComponent = new SlideComponent(false);
        myOpacityComponent.setToolTipText("Opacity");
        myOpacityComponent.addListener(new Consumer<Integer>() {
          @Override
          public void consume(Integer integer) {
            myColorWheel.setOpacity((integer / 100f));
            myColorWheel.repaint();
          }
        });

        add(myOpacityComponent, BorderLayout.SOUTH);
      }
    }

    public void setColor(Color color, Object source) {
      float[] hsb = new float[3];
      Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), hsb);

      myBrightnessComponent.setValue(100 - (int)(hsb[2] * 100));
      myBrightnessComponent.repaint();

      myColorWheel.dropImage();
      if (myOpacityComponent != null) {
        myOpacityComponent.setValue((int)((color.getAlpha() / 255.0) * 100));
        myOpacityComponent.repaint();

        myColorWheel.setColor(color, source);
      }
      else {
        myColorWheel.setColor(color, source);
      }
    }
  }

  private static class SlideComponent extends JComponent {
    private static final int OFFSET = 11;
    private int myPointerValue = 0;
    private int myValue = 0;
    private final boolean myVertical;

    private CopyOnWriteArrayList<Consumer<Integer>> myListeners = new CopyOnWriteArrayList<Consumer<Integer>>();

    private SlideComponent(boolean vertical) {
      myVertical = vertical;

      addMouseMotionListener(new MouseAdapter() {
        @Override
        public void mouseDragged(MouseEvent e) {
          processMouse(e);
        }
      });

      addMouseListener(new MouseAdapter() {
        @Override
        public void mousePressed(MouseEvent e) {
          processMouse(e);
        }
      });

      addMouseWheelListener(new MouseWheelListener() {
        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
          final int amount = e.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL ? e.getUnitsToScroll() * e.getScrollAmount() :
                             e.getWheelRotation() < 0 ? -e.getScrollAmount() : e.getScrollAmount();
          int pointerValue = myPointerValue + amount;
          pointerValue = pointerValue < OFFSET ? OFFSET : pointerValue;
          int size = myVertical ? getHeight() : getWidth();
          pointerValue = pointerValue > (size - 12) ? size - 12 : pointerValue;

          myPointerValue = pointerValue;
          myValue = pointerValueToValue(myPointerValue);

          repaint();
          fireValueChanged();
        }
      });

      addComponentListener(new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
          setValue(getValue());
          fireValueChanged();
          repaint();
        }
      });
    }

    private void processMouse(MouseEvent e) {
      int pointerValue = myVertical ? e.getY() : e.getX();
      pointerValue = pointerValue < OFFSET ? OFFSET : pointerValue;
      int size = myVertical ? getHeight() : getWidth();
      pointerValue = pointerValue > (size - 12) ? size - 12 : pointerValue;

      myPointerValue = pointerValue;

      myValue = pointerValueToValue(myPointerValue);

      repaint();
      fireValueChanged();
    }

    public void addListener(Consumer<Integer> listener) {
      myListeners.add(listener);
    }

    private void fireValueChanged() {
      for (Consumer<Integer> listener : myListeners) {
        listener.consume(myValue);
      }
    }

    // 0 - 100
    public void setValue(int value) {
      myPointerValue = valueToPointerValue(value);
      myValue = value;
    }

    public int getValue() {
      return myValue;
    }

    private int pointerValueToValue(int pointerValue) {
      pointerValue -= OFFSET;
      final int size = myVertical ? getHeight() : getWidth();
      float proportion = (size - 23) / 100f;
      return (int)(pointerValue / proportion);
    }

    private int valueToPointerValue(int value) {
      final int size = myVertical ? getHeight() : getWidth();
      float proportion = (size - 23) / 100f;
      return OFFSET + (int)(value * proportion);
    }

    @Override
    public Dimension getPreferredSize() {
      return myVertical ? new Dimension(22, 100) : new Dimension(100, 22);
    }

    @Override
    public Dimension getMinimumSize() {
      return myVertical ? new Dimension(22, 50) : new Dimension(50, 22);
    }

    @Override
    protected void paintComponent(Graphics g) {
      final Graphics2D g2d = (Graphics2D)g;

      if (myVertical) {
        g2d.setPaint(new GradientPaint(0f, 0f, Color.WHITE, 0f, getHeight(), Color.BLACK));
        g.fillRect(7, 10, 12, getHeight() - 20);

        g.setColor(Gray._150);
        g.drawRect(7, 10, 12, getHeight() - 20);

        g.setColor(Gray._250);
        g.drawRect(8, 11, 10, getHeight() - 22);
      }
      else {
        g2d.setPaint(new GradientPaint(0f, 0f, Color.WHITE, getWidth(), 0f, Color.BLACK));
        g.fillRect(10, 7, getWidth() - 20, 12);

        g.setColor(Gray._150);
        g.drawRect(10, 7, getWidth() - 20, 12);

        g.setColor(Gray._250);
        g.drawRect(11, 8, getWidth() - 22, 10);
      }

      drawKnob(g2d, myVertical ? 7 : myPointerValue, myVertical ? myPointerValue : 7, myVertical);
    }

    private static void drawKnob(Graphics2D g2d, int x, int y, boolean vertical) {
      g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      if (vertical) {
        y -= 6;

        Polygon arrowShadow = new Polygon();
        arrowShadow.addPoint(x - 5, y + 1);
        arrowShadow.addPoint(x + 7, y + 7);
        arrowShadow.addPoint(x - 5, y + 13);

        g2d.setColor(new Color(0, 0, 0, 70));
        g2d.fill(arrowShadow);

        Polygon arrowHead = new Polygon();
        arrowHead.addPoint(x - 6, y);
        arrowHead.addPoint(x + 6, y + 6);
        arrowHead.addPoint(x - 6, y + 12);

        g2d.setColor(new Color(153, 51, 0));
        g2d.fill(arrowHead);
      }
      else {
        x -= 6;

        Polygon arrowShadow = new Polygon();
        arrowShadow.addPoint(x + 1, y - 5);
        arrowShadow.addPoint(x + 13, y - 5);
        arrowShadow.addPoint(x + 7, y + 7);

        g2d.setColor(new Color(0, 0, 0, 70));
        g2d.fill(arrowShadow);

        Polygon arrowHead = new Polygon();
        arrowHead.addPoint(x, y - 6);
        arrowHead.addPoint(x + 12, y - 6);
        arrowHead.addPoint(x + 6, y + 6);

        g2d.setColor(new Color(153, 51, 0));
        g2d.fill(arrowHead);
      }
    }
  }

  private static class ColorWheel extends JComponent {
    private static final int BORDER_SIZE = 5;
    private float myBrightness = 1f;
    private float myHue = 1f;
    private float mySaturation = 0f;

    private Image myImage;
    private Rectangle myWheel;

    private boolean myShouldInvalidate = true;

    private Color myColor;

    private CopyOnWriteArrayList<ColorListener> myListeners = new CopyOnWriteArrayList<ColorListener>();
    private float myOpacity;

    private ColorWheel() {
      setOpaque(true);
      addComponentListener(new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
          myShouldInvalidate = true;
        }
      });

      addMouseMotionListener(new MouseAdapter() {
        @Override
        public void mouseDragged(MouseEvent e) {
          final int x = e.getX();
          final int y = e.getY();
          int mx = myWheel.x + myWheel.width / 2;
          int my = myWheel.y + myWheel.height / 2;
          double s;
          double h;
          s = Math.sqrt((double)((x - mx) * (x - mx) + (y - my) * (y - my))) / (myWheel.height / 2);
          h = -Math.atan2((double)(y - my), (double)(x - mx)) / (2 * Math.PI);
          if (h < 0) h += 1.0;
          if (s > 1) s = 1.0;

          setHSBValue((float)h, (float)s, myBrightness, myOpacity);
        }
      });

      addMouseListener(new MouseAdapter() {
        @Override
        public void mousePressed(MouseEvent e) {
          final int x = e.getX();
          final int y = e.getY();
          int mx = myWheel.x + myWheel.width / 2;
          int my = myWheel.y + myWheel.height / 2;
          double s;
          double h;
          s = Math.sqrt((double)((x - mx) * (x - mx) + (y - my) * (y - my))) / (myWheel.height / 2);
          h = -Math.atan2((double)(y - my), (double)(x - mx)) / (2 * Math.PI);
          if (h < 0) h += 1.0;
          if (s <= 1) {
            setHSBValue((float)h, (float)s, myBrightness, myOpacity);
          }
        }
      });
    }

    private void setHSBValue(float h, float s, float b, float opacity) {
      Color rgb = new Color(Color.HSBtoRGB(h, s, b));
      setColor(new Color(rgb.getRed(), rgb.getGreen(), rgb.getBlue(), (int)(255 * opacity)), this);
    }

    private void setColor(Color color, Object source) {
      float[] hsb = new float[3];
      Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), hsb);
      myColor = color;
      myHue = hsb[0];
      mySaturation = hsb[1];
      myBrightness = hsb[2];
      myOpacity = (float)(color.getAlpha() / 255.0);

      fireColorChanged(source);

      repaint();
    }

    public void addListener(ColorListener listener) {
      myListeners.add(listener);
    }

    private void fireColorChanged(Object source) {
      for (ColorListener listener : myListeners) {
        listener.colorChanged(myColor, source);
      }
    }

    public void setBrightness(float brightness) {
      if (brightness != myBrightness) {
        myImage = null;
        setHSBValue(myHue, mySaturation, brightness, myOpacity);
      }
    }


    public void setOpacity(float opacity) {
      if (opacity != myOpacity) {
        setHSBValue(myHue, mySaturation, myBrightness, opacity);
      }
    }


    @Override
    public Dimension getPreferredSize() {
      return getMinimumSize();
    }

    @Override
    public Dimension getMinimumSize() {
      return new Dimension(300, 300);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2d = (Graphics2D)g;

      final Dimension size = getSize();
      int _size = Math.min(size.width, size.height);
      _size = Math.min(_size, 600);

      if (myImage != null && myShouldInvalidate) {
        if (myImage.getWidth(null) != _size) {
          myImage = null;
        }
      }

      myShouldInvalidate = false;

      if (myImage == null) {
        myImage = createImage(new ColorWheelImageProducer(_size - BORDER_SIZE * 2, _size - BORDER_SIZE * 2, myBrightness));
        myWheel = new Rectangle(BORDER_SIZE, BORDER_SIZE, _size - BORDER_SIZE * 2, _size - BORDER_SIZE * 2);
      }

      g.setColor(UIManager.getColor("Panel.background"));
      g.fillRect(0, 0, getWidth(), getHeight());

      g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, myOpacity));
      g.drawImage(myImage, myWheel.x, myWheel.y, null);

      g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC, 1.0f));

      int mx = myWheel.x + myWheel.width / 2;
      int my = myWheel.y + myWheel.height / 2;
      g.setColor(Color.white);
      int arcw = (int)(myWheel.width * mySaturation / 2);
      int arch = (int)(myWheel.height * mySaturation / 2);
      double th = myHue * 2 * Math.PI;
      final int x = (int)(mx + arcw * Math.cos(th));
      final int y = (int)(my - arch * Math.sin(th));
      g.fillRect(x - 2, y - 2, 4, 4);
      g.setColor(Color.BLACK);
      g.drawRect(x - 2, y - 2, 4, 4);
    }

    public void dropImage() {
      myImage = null;
    }
  }

  private static class ColorPreviewComponent extends JComponent {
    private Color myColor;

    private ColorPreviewComponent() {
      setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(100, 32);
    }

    public void setColor(Color c) {
      myColor = c;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      final Insets i = getInsets();
      final Rectangle r = getBounds();

      final int width = r.width - i.left - i.right;
      final int height = r.height - i.top - i.bottom;

      g.setColor(Color.WHITE);
      g.fillRect(i.left, i.top, width, height);

      g.setColor(myColor);
      g.fillRect(i.left, i.top, width, height);

      g.setColor(Color.BLACK);
      g.drawRect(i.left, i.top, width - 1, height - 1);

      g.setColor(Color.WHITE);
      g.drawRect(i.left + 1, i.top + 1, width - 3, height - 3);
    }
  }

  public static class NumberDocument extends PlainDocument {

    private final boolean myHex;
    private JTextField mySrc;

    public NumberDocument(boolean hex) {
      myHex = hex;
    }

    void setSource(JTextField field) {
      mySrc = field;
    }
    public void insertString(int offs, String str, AttributeSet a) throws BadLocationException {
      char[] source = str.toCharArray();
      if (mySrc != null) {
        final int selected = mySrc.getSelectionEnd() - mySrc.getSelectionStart();
        int newLen = mySrc.getText().length() -  selected + str.length();
        if (newLen > (myHex ? 6 : 3)) {
          Toolkit.getDefaultToolkit().beep();
          return;
        }
      }
      char[] result = new char[source.length];
      int j = 0;
      for (int i = 0; i < result.length; i++) {
        if (myHex ? "0123456789abcdefABCDEF".indexOf(source[i]) >= 0 : Character.isDigit(source[i])) {
          result[j++] = source[i];
        }
        else {
          Toolkit.getDefaultToolkit().beep();
        }
      }
      final String toInsert = new String(result, 0, j).toUpperCase();
      final String res = new StringBuilder(mySrc.getText()).insert(offs, toInsert).toString();
      try {
        if (!myHex && Integer.parseInt(res) > 255) {
          Toolkit.getDefaultToolkit().beep();
          return;
        }
      }
      catch (NumberFormatException ignore) {
      }
      super.insertString(offs, toInsert, a);
    }
  }

  private class RecentColorsComponent extends JComponent {
    private static final int WIDTH = 10 * 30 + 13;
    private static final int HEIGHT = 62 + 3;

    private List<Color> myRecentColors = new ArrayList<Color>();

    private RecentColorsComponent(final ColorListener listener, boolean restoreColors) {
      addMouseListener(new MouseAdapter() {
        @Override
        public void mousePressed(MouseEvent e) {
          Color color = getColor(e);
          if (color != null) {
            listener.colorChanged(color, RecentColorsComponent.this);
          }
        }
      });

      if (restoreColors) {
        restoreColors();
      }
    }

    @Nullable
    public Color getMostRecentColor() {
      return myRecentColors.isEmpty() ? null : myRecentColors.get(myRecentColors.size() - 1);
    }

    private void restoreColors() {
      final String value = PropertiesComponent.getInstance().getValue(COLOR_CHOOSER_COLORS_KEY);
      if (value != null) {
        final List<String> colors = StringUtil.split(value, ",,,");
        for (String color : colors) {
          if (color.contains("-")) {
            List<String> components = StringUtil.split(color, "-");
            if (components.size() == 4) {
              myRecentColors.add(new Color(Integer.parseInt(components.get(0)),
                                           Integer.parseInt(components.get(1)),
                                           Integer.parseInt(components.get(2)),
                                           Integer.parseInt(components.get(3))));
            }
          }
          else {
            myRecentColors.add(new Color(Integer.parseInt(color)));
          }
        }
      }
    }

    @Override
    public String getToolTipText(MouseEvent event) {
      Color color = getColor(event);
      if (color != null) {
        return String.format("R: %d G: %d B: %d A: %s", color.getRed(), color.getGreen(), color.getBlue(),
                             String.format("%.2f", (float)(color.getAlpha() / 255.0)));
      }

      return super.getToolTipText(event);
    }

    @Nullable
    private Color getColor(MouseEvent event) {
      Pair<Integer, Integer> pair = pointToCellCoords(event.getPoint());
      if (pair != null) {
        int ndx = pair.second + pair.first * 10;
        if (myRecentColors.size() > ndx) {
          return myRecentColors.get(ndx);
        }
      }

      return null;
    }

    public void saveColors() {
      final List<String> values = new ArrayList<String>();
      for (Color recentColor : myRecentColors) {
        if (recentColor == null) break;
        values
          .add(String.format("%d-%d-%d-%d", recentColor.getRed(), recentColor.getGreen(), recentColor.getBlue(), recentColor.getAlpha()));
      }

      PropertiesComponent.getInstance().setValue(COLOR_CHOOSER_COLORS_KEY, StringUtil.join(values, ",,,"));
    }

    public void appendColor(Color c) {
      if (!myRecentColors.contains(c)) {
        myRecentColors.add(c);
      }

      if (myRecentColors.size() > 20) {
        myRecentColors = new ArrayList<Color>(myRecentColors.subList(myRecentColors.size() - 20, myRecentColors.size()));
      }
    }

    @Nullable
    private Pair<Integer, Integer> pointToCellCoords(Point p) {
      int x = p.x;
      int y = p.y;

      final Insets i = getInsets();
      final Dimension d = getSize();

      final int left = i.left + (d.width - i.left - i.right - WIDTH) / 2;
      final int top = i.top + (d.height - i.top - i.bottom - HEIGHT) / 2;

      int col = (x - left - 2) / 31;
      col = col > 9 ? 9 : col;
      int row = (y - top - 2) / 31;
      row = row > 1 ? 1 : row;

      return row >= 0 && col >= 0 ? Pair.create(row, col) : null;
    }

    @Override
    public Dimension getPreferredSize() {
      return getMinimumSize();
    }

    @Override
    public Dimension getMinimumSize() {
      return new Dimension(WIDTH, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
      final Insets i = getInsets();

      final Dimension d = getSize();

      final int left = i.left + (d.width - i.left - i.right - WIDTH) / 2;
      final int top = i.top + (d.height - i.top - i.bottom - HEIGHT) / 2;

      g.setColor(Color.WHITE);
      g.fillRect(left, top, WIDTH, HEIGHT);

      g.setColor(Color.GRAY);
      g.drawLine(left + 1, i.top + HEIGHT / 2, left + WIDTH - 3, i.top + HEIGHT / 2);
      g.drawRect(left + 1, top + 1, WIDTH - 3, HEIGHT - 3);


      for (int k = 1; k < 10; k++) {
        g.drawLine(left + 1 + k * 31, top + 1, left + 1 + k * 31, top + HEIGHT - 3);
      }

      for (int r = 0; r < myRecentColors.size(); r++) {
        int row = r / 10;
        int col = r % 10;
        Color color = myRecentColors.get(r);
        g.setColor(color);
        g.fillRect(left + 2 + col * 30 + col + 1, top + 2 + row * 30 + row + 1, 28, 28);
      }
    }
  }

  private static class ColorPickerDialog extends DialogWrapper {

    private final Color myPreselectedColor;
    private ColorPicker myColorPicker;
    private final boolean myEnableOpacity;
    private ColorPipette myPicker;

    public ColorPickerDialog(Component parent, String caption, Color preselectedColor, boolean enableOpacity) {
      super(parent, true);
      setTitle(caption);
      myPreselectedColor = preselectedColor;
      myEnableOpacity = enableOpacity;
      setResizable(false);
      setOKButtonText("Choose");
      init();
      addMouseListener((MouseMotionListener)new MouseAdapter() {
        @Override
        public void mouseEntered(MouseEvent e) {
          myPicker.cancelPipette();
        }

        @Override
        public void mouseExited(MouseEvent e) {
          myPicker.pick();
        }
      });

    }

    @Override
    protected JComponent createCenterPanel() {
      if (myColorPicker == null) {
        myColorPicker = new ColorPicker(myDisposable, myPreselectedColor, myEnableOpacity);
      }

      return myColorPicker;
    }

    public Color getColor() {
      return myColorPicker.getColor();
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
      return myColorPicker.getPreferredFocusedComponent();
    }

    @Override
    protected void doOKAction() {
      myColorPicker.appendRecentColor();
      myColorPicker.saveRecentColors();

      super.doOKAction();
    }
  }

  public static class ColorWheelImageProducer extends MemoryImageSource {
    private int[] myPixels;
    private int myWidth;
    private int myHeight;
    private float myBrightness = 1f;

    private float[] myHues;
    private float[] mySat;
    private int[] myAlphas;

    public ColorWheelImageProducer(int w, int h, float brightness) {
      super(w, h, null, 0, w);
      myPixels = new int[w * h];
      myWidth = w;
      myHeight = h;
      myBrightness = brightness;
      generateLookupTables();
      newPixels(myPixels, ColorModel.getRGBdefault(), 0, w);
      setAnimated(true);
      generateColorWheel();
    }

    public int getRadius() {
      return Math.min(myWidth, myHeight) / 2 - 2;
    }

    private void generateLookupTables() {
      mySat = new float[myWidth * myHeight];
      myHues = new float[myWidth * myHeight];
      myAlphas = new int[myWidth * myHeight];
      float radius = getRadius();

      // blend is used to create a linear alpha gradient of two extra pixels
      float blend = (radius + 2f) / radius - 1f;

      // Center of the color wheel circle
      int cx = myWidth / 2;
      int cy = myHeight / 2;

      for (int x = 0; x < myWidth; x++) {
        int kx = x - cx; // Kartesian coordinates of x
        int squarekx = kx * kx; // Square of kartesian x

        for (int y = 0; y < myHeight; y++) {
          int ky = cy - y; // Kartesian coordinates of y

          int index = x + y * myWidth;
          mySat[index] = (float)Math.sqrt(squarekx + ky
                                                     * ky)
                         / radius;
          if (mySat[index] <= 1f) {
            myAlphas[index] = 0xff000000;
          }
          else {
            myAlphas[index] = (int)((blend - Math.min(blend,
                                                      mySat[index] - 1f)) * 255 / blend) << 24;
            mySat[index] = 1f;
          }
          if (myAlphas[index] != 0) {
            myHues[index] = (float)(Math.atan2(ky, kx) / Math.PI / 2d);
          }
        }
      }
    }

    public void generateColorWheel() {
      for (int index = 0; index < myPixels.length; index++) {
        if (myAlphas[index] != 0) {
          myPixels[index] = myAlphas[index]
                            | 0xffffff
                              & Color.HSBtoRGB(myHues[index],
                                               mySat[index], myBrightness);
        }
      }
      newPixels();
    }
  }

  private static class ColorPipette implements ImageObserver {
    private Dialog myPickerFrame;
    private final JComponent myParent;
    private Color myOldColor;
    private Timer myTimer;

    private Point myPoint = new Point();
    private Point myPickOffset;
    private Robot myRobot = null;
    private Color myPreviousColor;
    private Point myPreviousLocation;
    private Rectangle myCaptureRect;
    private Graphics2D myGraphics;
    private BufferedImage myImage;
    private Point myHotspot;
    private Point myCaptureOffset;
    private BufferedImage myMagnifierImage;
    private Color myTransparentColor = new Color(0, true);
    private Rectangle myZoomRect;
    private Rectangle myGlassRect;
    private ColorListener myColorListener;
    private BufferedImage myMaskImage;
    private Alarm myColorListenersNotifier = new Alarm(Alarm.ThreadToUse.SWING_THREAD);

    private ColorPipette(JComponent parent, Color oldColor) {
      myParent = parent;
      myOldColor = oldColor;

      try {
        myRobot = new Robot();
      }
      catch (AWTException e) {
        // should not happen
      }
    }

    public void setListener(ColorListener colorListener) {
      myColorListener = colorListener;
    }

    public void pick() {
      Dialog picker = getPicker();
      picker.setVisible(true);
      myTimer.start();
      // it seems like it's the lowest value for opacity for mouse events to be processed correctly
      WindowManager.getInstance().setAlphaModeRatio(picker, SystemInfo.isMac ? 0.95f : 0.99f);
    }

    @Override
    public boolean imageUpdate(Image img, int flags, int x, int y, int width, int height) {
      return false;
    }

    private Dialog getPicker() {
      if (myPickerFrame == null) {
        Window owner = SwingUtilities.getWindowAncestor(myParent);
        if (owner instanceof Dialog) {
          myPickerFrame = new JDialog((Dialog)owner);
        }
        else if (owner instanceof Frame) {
          myPickerFrame = new JDialog((Frame)owner);
        }
        else {
          myPickerFrame = new JDialog(new JFrame());
        }

        myPickerFrame.addMouseListener(new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            e.consume();
            pickDone();
          }

          @Override
          public void mouseClicked(MouseEvent e) {
            e.consume();
          }

          @Override
          public void mouseExited(MouseEvent e) {
            updatePipette();
          }
        });

        myPickerFrame.addMouseMotionListener(new MouseAdapter() {
          @Override
          public void mouseMoved(MouseEvent e) {
            updatePipette();
          }
        });

        myPickerFrame.addFocusListener(new FocusAdapter() {
          @Override
          public void focusLost(FocusEvent e) {
            cancelPipette();
          }
        });

        myPickerFrame.setSize(50, 50);
        myPickerFrame.setUndecorated(true);
        myPickerFrame.setAlwaysOnTop(true);

        JRootPane rootPane = ((JDialog)myPickerFrame).getRootPane();
        rootPane.putClientProperty("Window.shadow", Boolean.FALSE);

        myGlassRect = new Rectangle(0, 0, 32, 32);
        myPickOffset = new Point(0, 0);
        myCaptureRect = new Rectangle(-4, -4, 8, 8);
        myCaptureOffset = new Point(myCaptureRect.x, myCaptureRect.y);
        myHotspot = new Point(14, 16);

        myZoomRect = new Rectangle(0, 0, 32, 32);

        myMaskImage = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D maskG = myMaskImage.createGraphics();
        maskG.setColor(Color.BLUE);
        maskG.fillRect(0, 0, 32, 32);

        maskG.setColor(Color.RED);
        maskG.setComposite(AlphaComposite.SrcOut);
        maskG.fillRect(0, 0, 32, 32);
        maskG.dispose();

        myMagnifierImage = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = myMagnifierImage.createGraphics();

        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        graphics.setColor(Color.BLACK);
        //graphics.drawOval(1, 1, 30, 30);
        //graphics.drawOval(2, 2, 28, 28);
        //
        //graphics.drawLine(2, 16, 12, 16);
        //graphics.drawLine(20, 16, 30, 16);
        //
        //graphics.drawLine(16, 2, 16, 12);
        //graphics.drawLine(16, 20, 16, 30);
        PICK.paintIcon(null, graphics, 14, 0);

        graphics.dispose();

        myImage = myParent.getGraphicsConfiguration().createCompatibleImage(myMagnifierImage.getWidth(), myMagnifierImage.getHeight(),
                                                                            Transparency.TRANSLUCENT);

        myGraphics = (Graphics2D)myImage.getGraphics();
        myGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        myPickerFrame.addKeyListener(new KeyAdapter() {
          public void keyPressed(KeyEvent e) {
            switch (e.getKeyCode()) {
              case KeyEvent.VK_ESCAPE:
                cancelPipette();
                break;
              case KeyEvent.VK_ENTER:
                pickDone();
                break;
            }
          }
        });

        myTimer = new Timer(5, new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            updatePipette();
          }
        });
      }

      return myPickerFrame;
    }

    private void cancelPipette() {
      myTimer.stop();
      myPickerFrame.setVisible(false);
      if (myColorListener != null && myOldColor != null) {
        myColorListener.colorChanged(myOldColor, this);
      }
    }

    public void pickDone() {
      cancelPipette();
      PointerInfo pointerInfo = MouseInfo.getPointerInfo();
      Point location = pointerInfo.getLocation();
      Color pixelColor = myRobot.getPixelColor(location.x + myPickOffset.x, location.y + myPickOffset.y);
      if (myColorListener != null) {
        myColorListener.colorChanged(pixelColor, this);
        myOldColor = pixelColor;
      }
    }

    private void updatePipette() {
      if (myPickerFrame != null && myPickerFrame.isShowing()) {
        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        Point mouseLoc = pointerInfo.getLocation();
        myPickerFrame.setLocation(mouseLoc.x - myPickerFrame.getWidth() / 2, mouseLoc.y - myPickerFrame.getHeight() / 2);

        myPoint.x = mouseLoc.x + myPickOffset.x;
        myPoint.y = mouseLoc.y + myPickOffset.y;

        final Color c = myRobot.getPixelColor(myPoint.x, myPoint.y);
        if (!c.equals(myPreviousColor) || !mouseLoc.equals(myPreviousLocation)) {
          myPreviousColor = c;
          myPreviousLocation = mouseLoc;
          myCaptureRect.setLocation(mouseLoc.x - 2/*+ myCaptureOffset.x*/, mouseLoc.y - 2/*+ myCaptureOffset.y*/);
          myCaptureRect.setBounds(mouseLoc.x -2, mouseLoc.y -2, 5, 5);

          BufferedImage capture = myRobot.createScreenCapture(myCaptureRect);

          // Clear the cursor graphics
          myGraphics.setComposite(AlphaComposite.Src);
          myGraphics.setColor(myTransparentColor);
          myGraphics.fillRect(0, 0, myImage.getWidth(), myImage.getHeight());

          myGraphics.drawImage(capture, myZoomRect.x, myZoomRect.y, myZoomRect.width, myZoomRect.height, this);

          // cropping round image
          myGraphics.setComposite(AlphaComposite.getInstance(AlphaComposite.DST_OUT));
          myGraphics.drawImage(myMaskImage, myZoomRect.x, myZoomRect.y, myZoomRect.width, myZoomRect.height, this);

          // paint magnifier
          myGraphics.setComposite(AlphaComposite.SrcOver);
          myGraphics.drawImage(myMagnifierImage, 0, 0, this);

          // We need to create a new subImage. This forces that
          // the color picker uses the new imagery.
          //BufferedImage subImage = myImage.getSubimage(0, 0, myImage.getWidth(), myImage.getHeight());
          myPickerFrame.setCursor(myParent.getToolkit().createCustomCursor(myImage, myHotspot, "ColorPicker"));
          if (myColorListener != null) {
            myColorListenersNotifier.cancelAllRequests();
            myColorListenersNotifier.addRequest(new Runnable() {
              @Override
              public void run() {
                myColorListener.colorChanged(c, ColorPipette.this);
              }
            }, 300);
          }
        }
      }
    }

    //public static void pickColor(ColorListener listener, JComponent c) {
    //  new ColorPipette(c, new ColorListener() {
    //    @Override
    //    public void colorChanged(Color color, Object source) {
    //      ColorPicker.this.setColor(color, my);
    //    }
    //  }).pick(listener);
    //}

    public static boolean isAvailable() {
      try {
        Robot robot = new Robot();
        robot.createScreenCapture(new Rectangle(0, 0, 1, 1));
        return WindowManager.getInstance().isAlphaModeSupported();
      }
      catch (AWTException e) {
        return false;
      }
    }
  }

  public static void main(String[] args) {
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
    ColorPicker.showDialog(null, "", null, true);
      }
    });
  }
}
interface ColorListener {
  void colorChanged(Color color, Object source);
}

