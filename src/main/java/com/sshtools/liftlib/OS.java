package com.sshtools.liftlib;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class OS {
	/**
	 * The type of desktop in use
	 *
	 */
	public enum Desktop {
		/**
		 * Gnome
		 */
		GNOME,
		/**
		 * KDE
		 */
		KDE,
		/**
		 * Cinnamon
		 */
		CINNAMON,
		/**
		 * XFCE
		 */
		XFCE,
		/**
		 * GNOME3
		 */
		GNOME3,
		/**
		 * Windows
		 */
		WINDOWS,
		/**
		 * Mac OS X
		 */
		MAC_OSX,
		/**
		 * Mac
		 */
		MAC,
		/**
		 * Other undetected
		 */
		OTHER,
		/**
		 * Unity
		 */
		UNITY,
		/**
		 * LXDE
		 */
		LXDE,
		/**
		 * Console
		 */
		CONSOLE,
		/**
		 * None
		 */
		NONE
	}

	public static final String OS_NAME = System.getProperty("os.name");

	private static final Map<String, Boolean> commandCache = new HashMap<>();

	/**
	 * Get if this environment is running on a desktop (and has access to the
	 * display)
	 * 
	 * @return running on desktop
	 */
	public static boolean isRunningOnDesktop() {
		return !getDesktopEnvironment().equals(Desktop.CONSOLE);
	}

	/**
	 * Get the current desktop environment in use.
	 * 
	 * @return desktop
	 */
	public static Desktop getDesktopEnvironment() {
		// TODO more to do - see the following links for lots of info

		// http://unix.stackexchange.com/questions/116539/how-to-detect-the-desktop-environment-in-a-bash-script
		// http://askubuntu.com/questions/72549/how-to-determine-which-window-manager-is-running/227669#227669

		String desktopSession = System.getenv("XDG_CURRENT_DESKTOP");
		String gdmSession = System.getenv("GDMSESSION");
		if (isWindows()) {
			return Desktop.WINDOWS;
		}
		if (isMacOs()) {
			return Desktop.MAC_OSX;
		}
		if (isLinux() && isBlank(System.getenv("DISPLAY"))) {
			return Desktop.CONSOLE;
		}

		if ("X-Cinnamon".equalsIgnoreCase(desktopSession)) {
			return Desktop.CINNAMON;
		}
		if ("LXDE".equalsIgnoreCase(desktopSession)) {
			return Desktop.LXDE;
		}
		if ("XFCE".equalsIgnoreCase(desktopSession)) {
			return Desktop.XFCE;
		}
		if ("KDE".equalsIgnoreCase(desktopSession) || (isBlank(desktopSession) && "kde-plasma".equals(gdmSession))) {
			return Desktop.KDE;
		}
		if ("UNITY".equalsIgnoreCase(desktopSession)) {
			return Desktop.UNITY;
		}
		if ("GNOME".equalsIgnoreCase(desktopSession)) {
			if ("gnome-shell".equals(gdmSession)) {
				return Desktop.GNOME3;
			}
			return Desktop.GNOME;
		}
		return Desktop.OTHER;
	}

	/**
	 * Get the username that would normally be used for administrator.
	 * 
	 * @return administrator username
	 */
	public static String getAdministratorUsername() {
		if (isWindows()) {
			return System.getProperty("liftlib.administratorUsername",
					System.getProperty("liftlib.rootUser", "Administrator"));
		}
		if (isUnixLike()) {
			return System.getProperty("liftlib.administratorUsername", System.getProperty("liftlib.rootUser", "root"));
		}
		throw new UnsupportedOperationException();
	}

	public static String getJavaPath() {
		String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
		if (isWindows()) {
			if (!javaExe.toLowerCase().endsWith("w")) {
				javaExe += "w";
			}
			javaExe += ".exe";
		}
		return javaExe;
	}

	public static boolean hasCommand(String command) {
		synchronized (commandCache) {
			Boolean val = commandCache.get(command);
			if (val == null) {
				boolean exists = doHasCommand(command);
				commandCache.put(command, exists);
				return exists;
			}
			return val;
		}
	}

	/**
	 * Get if currently running as an administrator.
	 * 
	 * @return administrator
	 */
	public static boolean isAdministrator() {
		if (isWindows()) {
			try {
				String programFiles = System.getenv("ProgramFiles");
				if (programFiles == null) {
					programFiles = "C:\\Program Files";
				}
				File temp = new File(programFiles, UUID.randomUUID().toString() + ".txt");
				temp.deleteOnExit();
				if (temp.createNewFile()) {
					temp.delete();
					return true;
				} else {
					return false;
				}
			} catch (IOException e) {
				return false;
			}
		}
		if (isUnixLike()) {
			return getAdministratorUsername().equals(System.getProperty("user.name"));
		}
		throw new UnsupportedOperationException();
	}

	public static boolean isAix() {
		return OS_NAME.toLowerCase().contains("aix");
	}

	public static boolean isBSD() {
		return OS_NAME.toLowerCase().contains("bsd");
	}

	public static boolean isLinux() {
		return OS_NAME.toLowerCase().contains("linux");
	}

	public static boolean isMacOs() {
		return OS_NAME.toLowerCase().contains("mac os");
	}

	public static boolean isSolaris() {
		return OS_NAME.toLowerCase().contains("sunos");
	}

	public static boolean isUnixLike() {
		return isLinux() || isMacOs() || isBSD() || isAix() || isSolaris();
	}

	public static boolean isWindows() {
		return OS_NAME.toLowerCase().contains("windows");
	}

	public static String expandModulePath(String modulePath) {
		var l = new ArrayList<String>();
		if(modulePath != null) {
			for(var path : modulePath.split(File.pathSeparator)) {
				var dir = Paths.get(path);
				if(Files.isDirectory(dir)) {
					try {
						var jars = false;
						for(var d : Files.list(dir).collect(Collectors.toList())) {
							if(d.getFileName().toString().endsWith(".jar")) {
								l.add(d.toAbsolutePath().toString());
								jars = true;
							}
						}
						if(!jars) {
							l.add(dir.toString());
						}
					}
					catch(IOException ioe) {
						throw new UncheckedIOException(ioe);
					}
				}
				else {
					l.add(path);
				}
			}
		}
		return l.isEmpty() ? null : String.join(File.pathSeparator, l);
	}

	protected static boolean doHasCommand(String command) {
		String path = System.getenv("PATH");
		if (path != "") {
			boolean found = false;
			for (String p : path.split(File.pathSeparator)) {
				File f = new File(p);
				if (f.isDirectory()) {
					String cmd = command;
					if (isWindows()) {
						cmd += ".exe";
					}
					File e = new File(f, cmd);
					if (e.exists()) {
						found = true;
						break;
					}
				}
			}
			return found;
		}
		throw new UnsupportedOperationException(
				OS_NAME + " is not supported. Cannot determine if command " + command + " exists");
	}

	private static boolean isBlank(String str) {
		return str == null || str.length() == 0;
	}

	private OS() {
	}
}
