package com.example.spotify;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * A small, undecorated, always-on-top widget separate from the RuneLite
 * sidebar — just transport controls + volume over a background picture/GIF
 * the user drags anywhere on screen. All methods here assume they're called
 * from the Swing EDT (same contract as SpotifyControllerPanel); the plugin
 * is responsible for that, not this class.
 */
class SpotifyMiniPlayerWindow
{
	interface Listener
	{
		void onPlayPauseClicked(boolean currentlyPlaying);

		void onPreviousClicked();

		void onNextClicked();

		void onVolumeChanged(int volumePercent);

		void onChooseBackgroundClicked();

		void onResetBackgroundClicked();

		void onCloseClicked();
	}

	private static final int DEFAULT_WIDTH = 280;
	private static final int DEFAULT_HEIGHT = 150;
	// Deliberately small — this is meant to stay a "mini" player regardless of
	// how large the source picture/GIF is. 500 (the original cap) was not mini.
	private static final int MAX_DIMENSION = 220;
	private static final int MIN_DIMENSION = 100;

	private final Listener listener;

	private JWindow window;
	private BackgroundPanel backgroundPanel;
	private JPanel controlBar;
	private JButton prevButton;
	private JButton playPauseButton;
	private JButton nextButton;
	private JSlider volumeSlider;
	private MouseAdapter hoverTracker;
	private Timer hideControlsTimer;

	private boolean currentlyPlaying;
	private boolean suppressVolumeEvent;

	SpotifyMiniPlayerWindow(Listener listener)
	{
		this.listener = listener;
	}

	void open(String backgroundImagePath)
	{
		if (window == null)
		{
			buildWindow();
		}
		setBackgroundImage(backgroundImagePath);
		window.setVisible(true);
	}

	void close()
	{
		if (window != null)
		{
			window.setVisible(false);
		}
	}

	boolean isOpen()
	{
		return window != null && window.isVisible();
	}

	void dispose()
	{
		if (window != null)
		{
			window.dispose();
			window = null;
			backgroundPanel = null;
		}
	}

	void setBackgroundImage(String path)
	{
		if (window == null)
		{
			buildWindow();
		}

		Image image = null;
		Dimension size = new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT);

		if (path != null && !path.trim().isEmpty())
		{
			// Toolkit.createImage() (not ImageIcon, not ImageIO.read) for two
			// reasons: (1) it's still a Toolkit-backed Image, so an animated GIF
			// actually animates — repaints get driven through the component's
			// ImageObserver on each new frame when painted via
			// drawImage(img, x, y, w, h, observer); ImageIO would flatten to one
			// frame. (2) unlike ImageIcon(path)/Toolkit.getImage(path), which
			// cache by file path for the life of the JVM, createImage() always
			// reads fresh — needed here since the same path can be rewritten
			// with different content (e.g. a regenerated GIF) between picks.
			Image loaded = java.awt.Toolkit.getDefaultToolkit().createImage(path);
			java.awt.MediaTracker tracker = new java.awt.MediaTracker(backgroundPanel);
			tracker.addImage(loaded, 0);
			try
			{
				tracker.waitForID(0);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
			int w = loaded.getWidth(null);
			int h = loaded.getHeight(null);
			if (!tracker.isErrorID(0) && w > 0 && h > 0)
			{
				image = loaded;
				size = scaledSize(w, h);
			}
		}

		window.setSize(size);
		backgroundPanel.setBackgroundImage(image);
	}

	/**
	 * setAlwaysOnTop(true) is only asserted once, at window creation — on
	 * Windows it can silently lose effect after another topmost/focus-stealing
	 * window (the game client) re-asserts itself, especially once the mini
	 * player hasn't been interacted with in a while, which reads as it having
	 * "disappeared" (it's actually just sunk behind the client). Called
	 * periodically off the existing poll cadence to re-assert it — no new
	 * scheduling machinery needed.
	 */
	void keepOnTop()
	{
		if (window != null && window.isVisible())
		{
			window.setAlwaysOnTop(true);
		}
	}

	void updatePlaybackState(SpotifyPlaybackState state)
	{
		if (window == null)
		{
			return;
		}
		currentlyPlaying = state.isPlaying;
		playPauseButton.setText(state.isPlaying ? "⏸" : "▶");

		suppressVolumeEvent = true;
		volumeSlider.setValue(state.volumePercent);
		suppressVolumeEvent = false;
	}

	private static Dimension scaledSize(int width, int height)
	{
		double scale = Math.min(1.0, (double) MAX_DIMENSION / Math.max(width, height));
		int scaledWidth = Math.max(MIN_DIMENSION, (int) Math.round(width * scale));
		int scaledHeight = Math.max(MIN_DIMENSION, (int) Math.round(height * scale));
		return new Dimension(scaledWidth, scaledHeight);
	}

	private void buildWindow()
	{
		window = new JWindow();
		window.setAlwaysOnTop(true);

		hoverTracker = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				showControls();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				scheduleHideControls();
			}
		};

		backgroundPanel = new BackgroundPanel();
		backgroundPanel.setLayout(new BorderLayout());
		controlBar = buildControlBar();
		controlBar.setVisible(false);
		backgroundPanel.add(controlBar, BorderLayout.SOUTH);
		backgroundPanel.addMouseListener(hoverTracker);
		window.setContentPane(backgroundPanel);

		installDragging(backgroundPanel);
		installContextMenu(backgroundPanel);

		window.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
		window.setLocation(150, 150);
	}

	/**
	 * Controls are hidden by default so an idle mini player just shows the
	 * picture/GIF; they fade in on hover. A short delay (rather than hiding
	 * immediately on mouseExited) absorbs the brief exit/re-entry blips Swing
	 * fires when the cursor crosses from one child component to a sibling
	 * (e.g. background panel -> a button), which would otherwise flicker.
	 */
	private void showControls()
	{
		if (hideControlsTimer != null)
		{
			hideControlsTimer.stop();
		}
		controlBar.setVisible(true);
		backgroundPanel.revalidate();
		backgroundPanel.repaint();
	}

	private void scheduleHideControls()
	{
		if (hideControlsTimer != null)
		{
			hideControlsTimer.stop();
		}
		hideControlsTimer = new Timer(200, e ->
		{
			controlBar.setVisible(false);
			backgroundPanel.revalidate();
			backgroundPanel.repaint();
		});
		hideControlsTimer.setRepeats(false);
		hideControlsTimer.start();
	}

	private JPanel buildControlBar()
	{
		TranslucentPanel bar = new TranslucentPanel(new Color(0, 0, 0, 70));
		bar.setLayout(new BorderLayout(0, 2));
		bar.setBorder(new EmptyBorder(4, 6, 4, 6));
		bar.addMouseListener(hoverTracker);

		JPanel transport = new JPanel(new GridLayout(1, 3, 4, 0));
		transport.setOpaque(false);
		prevButton = smallButton("◀◀");
		playPauseButton = smallButton("▶");
		nextButton = smallButton("▶▶");
		prevButton.addActionListener(e -> listener.onPreviousClicked());
		playPauseButton.addActionListener(e -> listener.onPlayPauseClicked(currentlyPlaying));
		nextButton.addActionListener(e -> listener.onNextClicked());
		prevButton.addMouseListener(hoverTracker);
		playPauseButton.addMouseListener(hoverTracker);
		nextButton.addMouseListener(hoverTracker);
		transport.add(prevButton);
		transport.add(playPauseButton);
		transport.add(nextButton);

		volumeSlider = new JSlider(0, 100, 100);
		volumeSlider.setOpaque(false);
		volumeSlider.addMouseListener(hoverTracker);
		volumeSlider.addChangeListener(e ->
		{
			if (!suppressVolumeEvent && !volumeSlider.getValueIsAdjusting())
			{
				listener.onVolumeChanged(volumeSlider.getValue());
			}
		});

		JPanel volumeRow = new JPanel(new BorderLayout(4, 0));
		volumeRow.setOpaque(false);
		volumeRow.add(volumeSlider, BorderLayout.CENTER);
		volumeRow.add(buildResizeGrip(), BorderLayout.EAST);

		bar.add(transport, BorderLayout.NORTH);
		bar.add(volumeRow, BorderLayout.SOUTH);
		return bar;
	}

	/**
	 * Small drag handle in the corner so the (undecorated, no OS resize
	 * border) window can still be resized manually — an image/GIF background
	 * is auto-fit small on load, but the user may still want it bigger/smaller.
	 */
	private JPanel buildResizeGrip()
	{
		JPanel grip = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g;
				g2.setColor(new Color(220, 220, 220, 180));
				int size = Math.min(getWidth(), getHeight());
				for (int i = 1; i <= 3; i++)
				{
					int offset = i * (size / 4);
					g2.drawLine(size - offset, size, size, size - offset);
				}
			}
		};
		grip.setOpaque(false);
		grip.setPreferredSize(new Dimension(14, 14));
		grip.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.SE_RESIZE_CURSOR));
		grip.addMouseListener(hoverTracker);
		installResizeDragging(grip);
		return grip;
	}

	private void installResizeDragging(Component grip)
	{
		MouseAdapter resizeHandler = new MouseAdapter()
		{
			private Point mouseStart;
			private Dimension sizeStart;

			@Override
			public void mousePressed(MouseEvent e)
			{
				mouseStart = e.getLocationOnScreen();
				sizeStart = window.getSize();
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				if (mouseStart == null)
				{
					return;
				}
				Point nowScreen = e.getLocationOnScreen();
				int newWidth = Math.max(MIN_DIMENSION, sizeStart.width + (nowScreen.x - mouseStart.x));
				int newHeight = Math.max(MIN_DIMENSION, sizeStart.height + (nowScreen.y - mouseStart.y));
				window.setSize(newWidth, newHeight);
			}
		};
		grip.addMouseListener(resizeHandler);
		grip.addMouseMotionListener(resizeHandler);
	}

	private static JButton smallButton(String text)
	{
		JButton button = new JButton(text);
		button.setMargin(new Insets(2, 4, 2, 4));
		button.setFocusPainted(false);
		// No filled button chrome — just the glyph floating over the
		// translucent bar, so it reads as part of a transparent overlay
		// rather than opaque UI boxes sitting on top of the picture.
		button.setContentAreaFilled(false);
		button.setBorderPainted(false);
		button.setOpaque(false);
		button.setForeground(Color.WHITE);
		return button;
	}

	/**
	 * Manual dragging since the window is undecorated (no OS title bar to drag
	 * by). Only responds to the left button so it doesn't fight the right-click
	 * context menu installed on the same component.
	 */
	private void installDragging(Component target)
	{
		MouseAdapter dragHandler = new MouseAdapter()
		{
			private Point mouseStart;
			private Point windowStart;

			@Override
			public void mousePressed(MouseEvent e)
			{
				if (!SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
				mouseStart = e.getLocationOnScreen();
				windowStart = window.getLocation();
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				if (mouseStart == null)
				{
					return;
				}
				Point nowScreen = e.getLocationOnScreen();
				window.setLocation(
					windowStart.x + (nowScreen.x - mouseStart.x),
					windowStart.y + (nowScreen.y - mouseStart.y));
			}
		};
		target.addMouseListener(dragHandler);
		target.addMouseMotionListener(dragHandler);
	}

	private void installContextMenu(Component target)
	{
		JPopupMenu menu = new JPopupMenu();

		JMenuItem chooseItem = new JMenuItem("Choose Background Image/GIF...");
		chooseItem.addActionListener(e -> listener.onChooseBackgroundClicked());

		JMenuItem resetItem = new JMenuItem("Reset to Default Look");
		resetItem.addActionListener(e -> listener.onResetBackgroundClicked());

		JMenuItem closeItem = new JMenuItem("Close Mini Player");
		closeItem.addActionListener(e -> listener.onCloseClicked());

		menu.add(chooseItem);
		menu.add(resetItem);
		menu.addSeparator();
		menu.add(closeItem);

		target.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShowPopup(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShowPopup(e);
			}

			private void maybeShowPopup(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					menu.show(e.getComponent(), e.getX(), e.getY());
				}
			}
		});
	}

	/**
	 * Draws either the chosen image/GIF stretched to fill the window (the
	 * window is already sized to the image's own aspect ratio, so this is a
	 * plain scale, not a crop), or — when nothing is chosen — a procedurally
	 * drawn OSRS-style parchment/stone-bevel look. No bundled game assets.
	 */
	private static class BackgroundPanel extends JPanel
	{
		private Image backgroundImage;

		BackgroundPanel()
		{
			setOpaque(true);
		}

		void setBackgroundImage(Image image)
		{
			this.backgroundImage = image;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g;
			if (backgroundImage != null)
			{
				g2.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), this);
			}
			else
			{
				paintDefaultLook(g2);
			}
		}

		private void paintDefaultLook(Graphics2D g2)
		{
			int w = getWidth();
			int h = getHeight();

			g2.setColor(new Color(20, 18, 14));
			g2.fillRect(0, 0, w, h);

			g2.setPaint(new GradientPaint(0, 0, new Color(151, 121, 76), 0, h, new Color(87, 68, 43)));
			g2.fillRect(4, 4, w - 8, h - 8);

			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(new Color(20, 18, 14));
			g2.setStroke(new BasicStroke(3));
			g2.drawRect(2, 2, w - 5, h - 5);
			g2.setColor(new Color(102, 89, 65));
			g2.setStroke(new BasicStroke(1));
			g2.drawRect(5, 5, w - 11, h - 11);
		}
	}

	private static class TranslucentPanel extends JPanel
	{
		private final Color fill;

		TranslucentPanel(Color fill)
		{
			this.fill = fill;
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setColor(fill);
			g2.fillRect(0, 0, getWidth(), getHeight());
			g2.dispose();
			super.paintComponent(g);
		}
	}
}
