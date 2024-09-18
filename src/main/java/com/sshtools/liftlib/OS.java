/**
 * Copyright © 2023 JAdaptive Limited (support@jadaptive.com)
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
package com.sshtools.liftlib;

import org.graalvm.nativeimage.ImageInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class OS {
            
	public static class CopyFileVisitor extends SimpleFileVisitor<Path> {
		private final Path targetPath;
		private Path sourcePath = null;

		public CopyFileVisitor(Path targetPath) {
			this.targetPath = targetPath;
		}

		@Override
		public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
			if (sourcePath == null) {
				sourcePath = dir;
			} else {
				Files.createDirectories(targetPath.resolve(sourcePath.relativize(dir)));
			}
			return FileVisitResult.CONTINUE;
		}

		@Override
		public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
			var rel = sourcePath.relativize(file);
			Files.copy(file, targetPath.resolve(rel), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
			return FileVisitResult.CONTINUE;
		}
	}

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

	private static final Map<String, Boolean> commandCache = new HashMap<>();
	private final static Optional<String> unixAdmin;
	
	static {
        Optional<String> s = Optional.empty();;
	    if(isUnixLike()) {
	        try {
	            var pb = new ProcessBuilder("id", "0");
	            var p = pb.start();
	            try(var rdr = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
	                var parts = rdr.readLine().split("\\s+")[0].substring(6);
	                s = Optional.of(parts.substring(0, parts.length() - 1));
	            }
	        }
	        catch(Exception e) {
	            s = Optional.empty();
	        }
	    }
        unixAdmin = s;
	}

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

		/* NOTE: This variable is to aid debugging. How Eclipse deals with environment
		 * variables in launchers is fucking stupid. Why can't you override a single
		 * existing variable? Append adds a 2nd XDG_CURRENT_DESKTOP, Replace wipes out all
		 * except anything specifically tested. It's nearly useless!
		 */
		String desktopSession = System.getenv("OVERRIDE_CURRENT_DESKTOP");
		if(desktopSession == null)
			desktopSession  = System.getenv("XDG_CURRENT_DESKTOP");
		String gdmSession = System.getenv("GDMSESSION");
		if (isWindows()) {
			return Desktop.WINDOWS;
		}
		if ("_CONSOLE_".equals(desktopSession) ||
			( isLinux() && isBlank(System.getenv("DISPLAY"))) ||  
			( isMacOs() && isBlank(System.getenv("XPC_FLAGS")))) {
			return Desktop.CONSOLE;
		}
		if (isMacOs()) {
			return Desktop.MAC_OSX;
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
	
	public static boolean isSharedLibrary() {
	    try {
	        return ImageInfo.isSharedLibrary();
	    }
	    catch(Throwable t) {
	        return false;
	    }
	}
	
	public static boolean isNativeImage() {
	    try {
	        return System.getProperty("java.home") == null || ImageInfo.isExecutable() || ImageInfo.isSharedLibrary();
	    }
	    catch(Throwable t) {
	        return false;
	    }
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
			return System.getProperty("liftlib.administratorUsername", System.getProperty("liftlib.rootUser", unixAdmin.orElse("root")));
		}
		throw new UnsupportedOperationException();
	}

	public static String getJavaPath() {
		String forceJava = System.getProperty("liftlib.jre");
		if(forceJava != null)
			return forceJava;
		String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
		if (isWindows()) {
			if (!Boolean.getBoolean("liftlib.alwaysConsole") && !Boolean.getBoolean("liftlib.debug") && !javaExe.toLowerCase().endsWith("w")) {
				javaExe += "w";
			}
			javaExe += ".exe";
		}
		return javaExe;
	}
	
	public static void copy(Path sourcePath, Path targetPath) throws IOException {
		Files.walkFileTree(sourcePath, new CopyFileVisitor(targetPath));
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
	
	public static boolean isElevated() {
		if (!isAdministrator())
			return false;
		if (isLinux()) {
			try {
				var pb = new ProcessBuilder("logname");
				var p = pb.start();
				try (var rdr = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
					return !getAdministratorUsername().equals(rdr.readLine());
				}
			} catch (Exception e) {
			}
			return false;
		} else {
			throw new UnsupportedOperationException();
		}
	}

    public static final String osName() {
        return System.getProperty("os.name"); 
    }

	public static boolean isAix() {
		return osName().toLowerCase().contains("aix");
	}

	public static boolean isBSD() {
		return osName().toLowerCase().contains("bsd");
	}

	public static boolean isLinux() {
		return osName().toLowerCase().contains("linux");
	}

	public static boolean isMacOs() {
		return osName().toLowerCase().contains("mac os");
	}

	public static boolean isSolaris() {
		return osName().toLowerCase().contains("sunos");
	}

	public static boolean isUnixLike() {
		return isLinux() || isMacOs() || isBSD() || isAix() || isSolaris();
	}

	public static boolean isWindows() {
		return osName().toLowerCase().contains("windows");
	}

	public static String expandModulePath(String modulePath) {
		var l = new ArrayList<String>();
		if(modulePath != null) {
			for(var path : modulePath.split(File.pathSeparator)) {
				if(!path.equals("")) {
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
		        osName() + " is not supported. Cannot determine if command " + command + " exists");
	}

	private static boolean isBlank(String str) {
		return str == null || str.length() == 0;
	}

	private OS() {
	}
}
