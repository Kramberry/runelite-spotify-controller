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
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
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
	private static final int MAX_DIMENSION = 500;
	private static final int MIN_DIMENSION = 120;

	private final Listener listener;

	private JWindow window;
	private BackgroundPanel backgroundPanel;
	private JButton prevButton;
	private JButton playPauseButton;
	private JButton nextButton;
	private JSlider volumeSlider;

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
			// ImageIcon (not ImageIO.read) is what makes an animated GIF actually
			// animate — its AWT Toolkit-backed Image drives repaints through the
			// component's ImageObserver on each new frame when painted via
			// drawImage(img, x, y, w, h, observer). ImageIO would flatten to one frame.
			ImageIcon icon = new ImageIcon(path);
			if (icon.getIconWidth() > 0 && icon.getIconHeight() > 0)
			{
				image = icon.getImage();
				size = scaledSize(icon.getIconWidth(), icon.getIconHeight());
			}
		}

		window.setSize(size);
		backgroundPanel.setBackgroundImage(image);
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

		backgroundPanel = new BackgroundPanel();
		backgroundPanel.setLayout(new BorderLayout());
		backgroundPanel.add(buildControlBar(), BorderLayout.SOUTH);
		window.setContentPane(backgroundPanel);

		installDragging(backgroundPanel);
		installContextMenu(backgroundPanel);

		window.setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
		window.setLocation(150, 150);
	}

	private JPanel buildControlBar()
	{
		TranslucentPanel bar = new TranslucentPanel(new Color(0, 0, 0, 150));
		bar.setLayout(new BorderLayout(0, 2));
		bar.setBorder(new EmptyBorder(4, 6, 4, 6));

		JPanel transport = new JPanel(new GridLayout(1, 3, 4, 0));
		transport.setOpaque(false);
		prevButton = smallButton("◀◀");
		playPauseButton = smallButton("▶");
		nextButton = smallButton("▶▶");
		prevButton.addActionListener(e -> listener.onPreviousClicked());
		playPauseButton.addActionListener(e -> listener.onPlayPauseClicked(currentlyPlaying));
		nextButton.addActionListener(e -> listener.onNextClicked());
		transport.add(prevButton);
		transport.add(playPauseButton);
		transport.add(nextButton);

		volumeSlider = new JSlider(0, 100, 100);
		volumeSlider.setOpaque(false);
		volumeSlider.addChangeListener(e ->
		{
			if (!suppressVolumeEvent && !volumeSlider.getValueIsAdjusting())
			{
				listener.onVolumeChanged(volumeSlider.getValue());
			}
		});

		bar.add(transport, BorderLayout.NORTH);
		bar.add(volumeSlider, BorderLayout.SOUTH);
		return bar;
	}

	private static JButton smallButton(String text)
	{
		JButton button = new JButton(text);
		button.setMargin(new Insets(2, 4, 2, 4));
		button.setFocusPainted(false);
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
