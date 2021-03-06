/*
 * Copyright 2019 FormDev Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.formdev.flatlaf;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.KeyEventPostProcessor;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.LookAndFeel;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.basic.BasicLookAndFeel;
import javax.swing.text.html.HTMLEditorKit;
import com.formdev.flatlaf.util.SystemInfo;
import com.formdev.flatlaf.util.UIScale;

/**
 * The base class for all Flat LaFs.
 *
 * @author Karl Tauber
 */
public abstract class FlatLaf
	extends BasicLookAndFeel
{
	static final Logger LOG = Logger.getLogger( FlatLaf.class.getName() );
	private static final String DESKTOPFONTHINTS = "awt.font.desktophints";

	private String desktopPropertyName;
	private PropertyChangeListener desktopPropertyListener;

	private static boolean aquaLoaded;
	private static boolean updateUIPending;

	private KeyEventPostProcessor mnemonicListener;
	private static boolean showMnemonics;
	private static WeakReference<Window> lastShowMnemonicWindow;

	private Consumer<UIDefaults> postInitialization;

	public static boolean install( LookAndFeel newLookAndFeel ) {
		try {
			UIManager.setLookAndFeel( newLookAndFeel );
			return true;
		} catch( Exception ex ) {
			LOG.log( Level.SEVERE, "FlatLaf: Failed to initialize look and feel '" + newLookAndFeel.getClass().getName() + "'.", ex );
			return false;
		}
	}

	/**
	 * Returns the look and feel identifier.
	 * <p>
	 * Syntax: "FlatLaf - ${theme-name}"
	 * <p>
	 * Use {@code UIManager.getLookAndFeel().getID().startsWith( "FlatLaf" )}
	 * to check whether the current look and feel is FlatLaf.
	 */
	@Override
	public String getID() {
		return "FlatLaf - " + getName();
	}

	public abstract boolean isDark();

	@Override
	public boolean isNativeLookAndFeel() {
		return false;
	}

	@Override
	public boolean isSupportedLookAndFeel() {
		return true;
	}

	@Override
	public void initialize() {
		if( SystemInfo.IS_MAC )
			initializeAqua();

		super.initialize();

		// add mnemonic listener
		mnemonicListener = e -> {
			checkShowMnemonics( e );
			return false;
		};
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventPostProcessor( mnemonicListener );

		// listen to desktop property changes to update UI if system font or scaling changes
		if( SystemInfo.IS_WINDOWS ) {
			// Windows 10 allows increasing font size independent of scaling:
			//   Settings > Ease of Access > Display > Make text bigger (100% - 225%)
			desktopPropertyName = "win.messagebox.font";
		} else if( SystemInfo.IS_LINUX ) {
			// Linux/Gnome allows extra scaling and larger text:
			//   Settings > Devices > Displays > Scale (100% or 200%)
			//   Settings > Universal access > Large Text (off or on, 125%)
			desktopPropertyName = "gnome.Xft/DPI";
		}
		if( desktopPropertyName != null ) {
			desktopPropertyListener = e -> {
				String propertyName = e.getPropertyName();
				if( desktopPropertyName.equals( propertyName ) )
					reSetLookAndFeel();
				else if( DESKTOPFONTHINTS.equals( propertyName ) ) {
					if( UIManager.getLookAndFeel() instanceof FlatLaf ) {
						putAATextInfo( UIManager.getLookAndFeelDefaults() );
						updateUILater();
					}
				}
			};
			Toolkit toolkit = Toolkit.getDefaultToolkit();
			toolkit.addPropertyChangeListener( desktopPropertyName, desktopPropertyListener );
			toolkit.addPropertyChangeListener( DESKTOPFONTHINTS, desktopPropertyListener );
		}

		// Following code should be ideally in initialize(), but needs color from UI defaults.
		// Do not move this code to getDefaults() to avoid side effects in the case that
		// getDefaults() is directly invoked from 3rd party code. E.g. `new FlatLightLaf().getDefaults()`.
		postInitialization = defaults -> {
			// update link color in HTML text
			Color linkColor = defaults.getColor( "Component.linkColor" );
			if( linkColor != null ) {
				new HTMLEditorKit().getStyleSheet().addRule(
					String.format( "a { color: #%06x; }", linkColor.getRGB() & 0xffffff ) );
			}
		};
	}

	@Override
	public void uninitialize() {
		// remove desktop property listener
		if( desktopPropertyListener != null ) {
			Toolkit toolkit = Toolkit.getDefaultToolkit();
			toolkit.removePropertyChangeListener( desktopPropertyName, desktopPropertyListener );
			toolkit.removePropertyChangeListener( DESKTOPFONTHINTS, desktopPropertyListener );
			desktopPropertyName = null;
			desktopPropertyListener = null;
		}

		// remove mnemonic listener
		if( mnemonicListener != null ) {
			KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventPostProcessor( mnemonicListener );
			mnemonicListener = null;
		}

		// restore default link color
		new HTMLEditorKit().getStyleSheet().addRule( "a { color: blue; }" );
		postInitialization = null;

		super.uninitialize();
	}

	/**
	 * Initialize Aqua LaF on macOS, which is required for using Mac screen menubar.
	 * (at least on Java 8, since 9 it seems to work without it)
	 * <p>
	 * This loads the native library "osxui" and initializes JRSUI.
	 * Because both are not unloaded/uninitialized, Aqua LaF is initialized only once.
	 */
	private void initializeAqua() {
		if( aquaLoaded )
			return;

		aquaLoaded = true;

		// create macOS Aqua LaF
		String aquaLafClassName = "com.apple.laf.AquaLookAndFeel";
		BasicLookAndFeel aquaLaf;
		try {
			if( SystemInfo.IS_JAVA_9_OR_LATER ) {
				Method m = UIManager.class.getMethod( "createLookAndFeel", String.class );
				aquaLaf = (BasicLookAndFeel) m.invoke( null, "Mac OS X" );
			} else
				aquaLaf = (BasicLookAndFeel) Class.forName( aquaLafClassName ).newInstance();
		} catch( Exception ex ) {
			LOG.log( Level.SEVERE, "FlatLaf: Failed to initialize Aqua look and feel '" + aquaLafClassName + "'.", ex );
			throw new IllegalStateException();
		}

		// remember popup factory because aquaLaf.initialize() installs its own
		// factory, which makes sub-menu rendering "jittery"
		PopupFactory oldPopupFactory = PopupFactory.getSharedInstance();

		// initialize Aqua LaF
		aquaLaf.initialize();
		aquaLaf.uninitialize();

		// restore popup factory
		PopupFactory.setSharedInstance( oldPopupFactory );
	}

	@Override
	public UIDefaults getDefaults() {
		UIDefaults defaults = super.getDefaults();

		// add Metal resource bundle, which is required for FlatFileChooserUI
		defaults.addResourceBundle( "com.sun.swing.internal.plaf.metal.resources.metal" );

		// initialize some defaults (for overriding) that are used in UI delegates,
		// but are not set in BasicLookAndFeel
		putDefaults( defaults, defaults.getColor( "control" ),
			"EditorPane.disabledBackground",
			"EditorPane.inactiveBackground",
			"FormattedTextField.disabledBackground",
			"PasswordField.disabledBackground",
			"Spinner.disabledBackground",
			"TextArea.disabledBackground",
			"TextArea.inactiveBackground",
			"TextField.disabledBackground",
			"TextPane.disabledBackground",
			"TextPane.inactiveBackground" );
		putDefaults( defaults, defaults.getColor( "textInactiveText" ),
			"Button.disabledText",
			"CheckBox.disabledText",
			"CheckBoxMenuItem.disabledForeground",
			"Menu.disabledForeground",
			"MenuItem.disabledForeground",
			"RadioButton.disabledText",
			"RadioButtonMenuItem.disabledForeground",
			"Spinner.disabledForeground",
			"ToggleButton.disabledText" );
		putDefaults( defaults, defaults.getColor( "textText" ),
			"DesktopIcon.foreground" );

		initFonts( defaults );
		initIconColors( defaults, isDark() );
		FlatInputMaps.initInputMaps( defaults );

		// load defaults from properties
		List<Class<?>> lafClassesForDefaultsLoading = getLafClassesForDefaultsLoading();
		if( lafClassesForDefaultsLoading != null )
			UIDefaultsLoader.loadDefaultsFromProperties( lafClassesForDefaultsLoading, defaults );
		else
			UIDefaultsLoader.loadDefaultsFromProperties( getClass(), defaults );

		// use Aqua MenuBarUI if Mac screen menubar is enabled
		if( SystemInfo.IS_MAC && Boolean.getBoolean( "apple.laf.useScreenMenuBar" ) )
			defaults.put( "MenuBarUI", "com.apple.laf.AquaMenuBarUI" );

		// initialize text antialiasing
		putAATextInfo( defaults );

		invokePostInitialization( defaults );

		return defaults;
	}

	void invokePostInitialization( UIDefaults defaults ) {
		if( postInitialization != null ) {
			postInitialization.accept( defaults );
			postInitialization = null;
		}
	}

	List<Class<?>> getLafClassesForDefaultsLoading() {
		return null;
	}

	private void initFonts( UIDefaults defaults ) {
		FontUIResource uiFont = null;

		if( SystemInfo.IS_WINDOWS ) {
			Font winFont = (Font) Toolkit.getDefaultToolkit().getDesktopProperty( "win.messagebox.font" );
			if( winFont != null )
				uiFont = new FontUIResource( winFont );

		} else if( SystemInfo.IS_MAC ) {
			String fontName;
			if( SystemInfo.IS_MAC_OS_10_11_EL_CAPITAN_OR_LATER ) {
				// use San Francisco Text font
				fontName = ".SF NS Text";
			} else {
				// default font on older systems (see com.apple.laf.AquaFonts)
				fontName = "Lucida Grande";
			}
			uiFont = new FontUIResource( fontName, Font.PLAIN, 13 );

		} else if( SystemInfo.IS_LINUX ) {
			Font font = LinuxFontPolicy.getFont();
			uiFont = (font instanceof FontUIResource) ? (FontUIResource) font : new FontUIResource( font );
		}

		if( uiFont == null )
			return;

		uiFont = UIScale.applyCustomScaleFactor( uiFont );

		// override fonts
		for( Object key : defaults.keySet() ) {
			if( key instanceof String && (((String)key).endsWith( ".font" ) || ((String)key).endsWith( "Font" )) )
				defaults.put( key, uiFont );
		}

		// use smaller font for progress bar
		defaults.put( "ProgressBar.font", UIScale.scaleFont( uiFont, 0.85f ) );
	}

	/**
	 * Adds the default color palette for action icons and object icons to the given UIDefaults.
	 * <p>
	 * This method is public and static to allow using the color palette with
	 * other LaFs (e.g. Windows LaF). To do so invoke:
	 *   {@code FlatLaf.initIconColors( UIManager.getLookAndFeelDefaults(), false );}
	 * after
	 *   {@code UIManager.setLookAndFeel( ... );}.
	 * <p>
	 * The colors are based on IntelliJ Platform
	 *   <a href="https://jetbrains.design/intellij/principles/icons/#action-icons">Action icons</a>
	 * and
	 *   <a href="https://jetbrains.design/intellij/principles/icons/#noun-icons">Noun icons</a>
	 */
	public static void initIconColors( UIDefaults defaults, boolean dark ) {
		// colors for action icons
		// see https://jetbrains.design/intellij/principles/icons/#action-icons
		defaults.put( "Actions.Red",            new ColorUIResource( !dark ? 0xDB5860 : 0xC75450 ) );
		defaults.put( "Actions.Yellow",         new ColorUIResource( !dark ? 0xEDA200 : 0xF0A732 ) );
		defaults.put( "Actions.Green",          new ColorUIResource( !dark ? 0x59A869 : 0x499C54 ) );
		defaults.put( "Actions.Blue",           new ColorUIResource( !dark ? 0x389FD6 : 0x3592C4 ) );
		defaults.put( "Actions.Grey",           new ColorUIResource( !dark ? 0x6E6E6E : 0xAFB1B3 ) );
		defaults.put( "Actions.GreyInline",     new ColorUIResource( !dark ? 0x7F8B91 : 0x7F8B91 ) );

		// colors for object icons
		// see https://jetbrains.design/intellij/principles/icons/#noun-icons
		defaults.put( "Objects.Grey",           new ColorUIResource( 0x9AA7B0 ) );
		defaults.put( "Objects.Blue",           new ColorUIResource( 0x40B6E0 ) );
		defaults.put( "Objects.Green",          new ColorUIResource( 0x62B543 ) );
		defaults.put( "Objects.Yellow",         new ColorUIResource( 0xF4AF3D ) );
		defaults.put( "Objects.YellowDark",     new ColorUIResource( 0xD9A343 ) );
		defaults.put( "Objects.Purple",         new ColorUIResource( 0xB99BF8 ) );
		defaults.put( "Objects.Pink",           new ColorUIResource( 0xF98B9E ) );
		defaults.put( "Objects.Red",            new ColorUIResource( 0xF26522 ) );
		defaults.put( "Objects.RedStatus",      new ColorUIResource( 0xE05555 ) );
		defaults.put( "Objects.GreenAndroid",   new ColorUIResource( 0xA4C639 ) );
		defaults.put( "Objects.BlackText",      new ColorUIResource( 0x231F20 ) );
	}

	private void putAATextInfo( UIDefaults defaults ) {
		if( SystemInfo.IS_JAVA_9_OR_LATER ) {
			Object desktopHints = Toolkit.getDefaultToolkit().getDesktopProperty( DESKTOPFONTHINTS );
			if( desktopHints instanceof Map ) {
				@SuppressWarnings( "unchecked" )
				Map<Object, Object> hints = (Map<Object, Object>) desktopHints;
				Object aaHint = hints.get( RenderingHints.KEY_TEXT_ANTIALIASING );
				if( aaHint != null &&
					aaHint != RenderingHints.VALUE_TEXT_ANTIALIAS_OFF &&
					aaHint != RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT )
				{
					defaults.put( RenderingHints.KEY_TEXT_ANTIALIASING, aaHint );
					defaults.put( RenderingHints.KEY_TEXT_LCD_CONTRAST,
						hints.get( RenderingHints.KEY_TEXT_LCD_CONTRAST ) );
				}
			}
		} else {
			// Java 8
			try {
				Object key = Class.forName( "sun.swing.SwingUtilities2" )
					.getField( "AA_TEXT_PROPERTY_KEY" )
					.get( null );
				Object value = Class.forName( "sun.swing.SwingUtilities2$AATextInfo" )
					.getMethod( "getAATextInfo", boolean.class )
					.invoke( null, true );
				defaults.put( key, value );
			} catch( Exception ex ) {
				Logger.getLogger( FlatLaf.class.getName() ).log( Level.SEVERE, null, ex );
				throw new RuntimeException( ex );
			}
		}
	}

	private void putDefaults( UIDefaults defaults, Object value, String... keys ) {
		for( String key : keys )
			defaults.put( key, value );
	}

	private static void reSetLookAndFeel() {
		EventQueue.invokeLater( () -> {
			LookAndFeel lookAndFeel = UIManager.getLookAndFeel();
			try {
				// re-set current LaF
				UIManager.setLookAndFeel( lookAndFeel );

				// must fire property change events ourself because old and new LaF are the same
				PropertyChangeEvent e = new PropertyChangeEvent( UIManager.class, "lookAndFeel", lookAndFeel, lookAndFeel );
				for( PropertyChangeListener l : UIManager.getPropertyChangeListeners() )
					l.propertyChange( e );

				// update UI
				updateUI();
			} catch( UnsupportedLookAndFeelException ex ) {
				LOG.log( Level.SEVERE, "FlatLaf: Failed to reinitialize look and feel '" + lookAndFeel.getClass().getName() + "'.", ex );
			}
		} );
	}

	/**
	 * Update UI of all application windows immediately.
	 * Invoke after changing LaF.
	 */
	public static void updateUI() {
		for( Window w : Window.getWindows() )
			SwingUtilities.updateComponentTreeUI( w );
	}

	/**
	 * Update UI of all application windows later.
	 */
	public static void updateUILater() {
		synchronized( FlatLaf.class ) {
			if( updateUIPending )
				return;

			updateUIPending = true;
		}

		EventQueue.invokeLater( () -> {
			updateUI();
			synchronized( FlatLaf.class ) {
				updateUIPending = false;
			}
		} );
	}

	public static boolean isShowMnemonics() {
		return showMnemonics || !UIManager.getBoolean( "Component.hideMnemonics" );
	}

	private static void checkShowMnemonics( KeyEvent e ) {
		int keyCode = e.getKeyCode();
		if( SystemInfo.IS_MAC ) {
			// Ctrl+Alt keys must be pressed on Mac
			if( keyCode == KeyEvent.VK_CONTROL || keyCode == KeyEvent.VK_ALT )
				showMnemonics( e.getID() == KeyEvent.KEY_PRESSED && e.isControlDown() && e.isAltDown(), e.getComponent() );
		} else {
			// Alt key must be pressed on Windows and Linux
			if( keyCode == KeyEvent.VK_ALT )
				showMnemonics( e.getID() == KeyEvent.KEY_PRESSED, e.getComponent() );
		}
	}

	private static void showMnemonics( boolean show, Component c ) {
		if( show == showMnemonics )
			return;

		showMnemonics = show;

		// check whether it is necessary to repaint
		if( !UIManager.getBoolean( "Component.hideMnemonics" ) )
			return;

		if( show ) {
			// get root pane
			JRootPane rootPane = SwingUtilities.getRootPane( c );
			if( rootPane == null )
				return;

			// get window
			Window window = SwingUtilities.getWindowAncestor( rootPane );
			if( window == null )
				return;

			// repaint components with mnemonics in focused window
			repaintMnemonics( window );

			lastShowMnemonicWindow = new WeakReference<>( window );
		} else if( lastShowMnemonicWindow != null ) {
			Window window = lastShowMnemonicWindow.get();
			if( window != null )
				repaintMnemonics( window );

			lastShowMnemonicWindow = null;
		}
	}

	private static void repaintMnemonics( Container container ) {
		for( Component c : container.getComponents() ) {
			if( !c.isVisible() )
				continue;

			if( hasMnemonic( c ) )
				c.repaint();

			if( c instanceof Container )
				repaintMnemonics( (Container) c );
		}
	}

	private static boolean hasMnemonic( Component c ) {
		if( c instanceof JLabel && ((JLabel)c).getDisplayedMnemonicIndex() >= 0 )
			return true;

		if( c instanceof AbstractButton && ((AbstractButton)c).getDisplayedMnemonicIndex() >= 0 )
			return true;

		if( c instanceof JTabbedPane ) {
			JTabbedPane tabPane = (JTabbedPane) c;
			int tabCount = tabPane.getTabCount();
			for( int i = 0; i < tabCount; i++ ) {
				if( tabPane.getDisplayedMnemonicIndexAt( i ) >= 0 )
					return true;
			}
		}

		return false;
	}
}
