package com.sshtools.liftlib.impl;

import static com.sshtools.liftlib.OS.expandModulePath;
import static com.sshtools.liftlib.OS.getDesktopEnvironment;
import static com.sshtools.liftlib.OS.hasCommand;
import static com.sshtools.liftlib.OS.isLinux;
import static com.sshtools.liftlib.OS.isMacOs;
import static com.sshtools.liftlib.OS.isWindows;

import java.io.Console;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.sshtools.liftlib.OS.Desktop;
import com.sshtools.liftlib.ui.AskPass;
import com.sshtools.liftlib.ui.AskPassConsole;

public interface PlatformElevation {

	static PlatformElevation forEnvironment(Optional<String> username, Optional<char[]> password) {
		if (isLinux()) {
			if (password.isPresent()) {
				return new SudoFixedPasswordUser(password.get());
			} else {
				Desktop dt = getDesktopEnvironment();
				if (Arrays.asList(Desktop.CINNAMON, Desktop.GNOME, Desktop.GNOME3).contains(dt)) {
					if (hasCommand("pkexec")) {
						return new PkExecUser(username);
					} else if (hasCommand("sudo")) {
						return new SudoAskPassGuiUser(username);
					}
				} else if (dt == Desktop.CONSOLE) {
					Console console = System.console();
					if (hasCommand("sudo") && console == null)
						return new SudoAskPassUser(username);
					else {
						if (hasCommand("sudo") || hasCommand("su")) {
							return new SUAdministrator();
						}
					}
				} else {
					// Unknown desktop
					return new SudoAskPassGuiUser(username);
				}
			}
		} else if (isMacOs()) {
			if (username.isPresent() || !hasCommand("osascript")) {
				if (password.isPresent()) {
					return new SudoFixedPasswordUser(password.get());
				} else if (hasCommand("sudo")) {
					return new SudoAskPassGuiUser(username);
				}
			} else {
				return new OsaScriptAsAdministrator();
			}
		} else if (isWindows()) {
			return new RunAsUser(username);
		}
		throw new UnsupportedOperationException(System.getProperty("os.name")
				+ " is currently unsupported. Will not be able to get administrative user. "
				+ "You can provide a password to the builder, but  this is unsafe, as the password will exist "
				+ "in a file for the life of the process. Do NOT use this in a production environment.");

	}

	void lower();

	void elevate(ProcessBuilder builder);

	/**
	 * Abstract implementation of an {@link PlatformElevation} that can be used to
	 * create a temporary script that will be used as part of the privilege
	 * escalation or user switching process.
	 *
	 */
	public static abstract class AbstractProcessBuilderEffectiveUser implements PlatformElevation {
		File tempScript;

		protected void createTempScript(String script) {
			// Create a temporary script to use to launch AskPass
			try {
				tempScript = File.createTempFile("sapa", ".sh");
				tempScript.deleteOnExit();
				var out = new FileOutputStream(tempScript);
				var pw = new PrintWriter(out);
				try {
					pw.println("#!/bin/bash");
					pw.println(script);
					pw.println("ret=$?");
					pw.println("rm -f '" + tempScript.getAbsolutePath() + "'");
					pw.println("exit ${ret}");
					pw.flush();
				} finally {
					out.close();
				}
				tempScript.setExecutable(true);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		protected String javaAskPassScript(Class<?> clazz) {
			var javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
			if (isWindows())
				javaExe += ".exe";

			var cp = System.getProperty("java.class.path");
			var mp = expandModulePath(System.getProperty("jdk.module.path"));
			if (mp != null) {
				if (cp == null)
					cp = mp;
				else
					cp = cp + File.pathSeparator + mp;
			}

			var b = new StringBuilder();
			b.append("\"");
			b.append(javaExe);
			b.append("\" ");
			if (cp != null) {
				b.append(" -classpath \"");
				b.append(cp);
				b.append("\"");
			}
			if (!mp.isEmpty()) {
				b.append(" -p \"");
				b.append(String.join(File.pathSeparator, mp));
				b.append("\"");
			}
			b.append(" ");
			if (!mp.isEmpty())
				b.append("-m com.sshtools.liftlib/");
			b.append(clazz.getName());
			return b.toString();
		}
	}

	public static class SudoFixedPasswordUser extends AbstractProcessBuilderEffectiveUser implements PlatformElevation {
		private char[] password;
		private String username;

		SudoFixedPasswordUser(char[] password) {
			this(null, password);
		}

		SudoFixedPasswordUser(String username, char[] password) {
			this.username = username;
			this.password = password;
		}

		@Override
		public void lower() {
		}

		@Override
		public void elevate(ProcessBuilder builder) {
			createTempScript("echo '" + new String(password).replace("'", "\\'") + "'");
			var cmd = builder.command();
			cmd.add(0, "sudo");
			cmd.add(1, "-A");
			if (username != null) {
				cmd.add(2, "-u");
				cmd.add(3, username);
			}
			builder.environment().put("SUDO_ASKPASS", tempScript.getAbsolutePath());
		}
	}

	public static class PkExecUser extends AbstractProcessBuilderEffectiveUser implements PlatformElevation {
		private Optional<String> username;

		PkExecUser(Optional<String> username) {
			this.username = username;
		}

		@Override
		public void lower() {
		}

		@Override
		public void elevate(ProcessBuilder builder) {
			var cmd = builder.command();
			cmd.add(0, "pkexec");
			username.ifPresent(u -> {
				cmd.add(1, "--user");
				cmd.add(2, u);
			});
		}
	}

	public static class OsaScriptAsAdministrator extends AbstractProcessBuilderEffectiveUser
			implements PlatformElevation {

		private OsaScriptAsAdministrator() {
		}

		@Override
		public void lower() {
		}

		@Override
		public void elevate(ProcessBuilder builder) {

			var cmd = builder.command();
			var bui = getQuotedCommandString(cmd);

			cmd.clear();
			cmd.add("osascript");
			cmd.add("-e");
			cmd.add(String.format("do shell script \"%s\" with administrator privileges", bui.toString()));
		}
	}

	public static class RunAsUser extends AbstractProcessBuilderEffectiveUser implements PlatformElevation {
		private Optional<String> username;

		private RunAsUser(Optional<String> username) {
			this.username = username;
		}

		@Override
		public void lower() {
		}

		@Override
		public void elevate(ProcessBuilder builder) {

			var cmd = builder.command();

			var exe = cmd.remove(0);
			var args = new ArrayList<String>(cmd).stream().map(s -> "\"\"\"" + s + "\"\"\""
			).collect(Collectors.toList());

			cmd.clear();
			cmd.add("powershell");
			username.ifPresent(u -> {
				cmd.add(1, "-credential");
				cmd.add(2, u);
			});
			cmd.add("-command");

			String powerShell;
			if (args.isEmpty()) {
				powerShell = String.format("Start-Process -Wait -FilePath \"\"\"%s\"\"\" -verb RunAs", exe);
			} else {
				powerShell = String.format("Start-Process -Wait -FilePath \"\"\"%s\"\"\" -ArgumentList %s -verb RunAs",
						exe, String.join(",", args));
			}

			cmd.add(String.format("&{%s}", powerShell));

		}
	}

	/**
	 * An {@link PlatformElevation} implementation that uses the 'sudo' command and
	 * the {@link AskPass} application to request a password for a particular user.
	 * The advantage of this over plain sudo based implementations, is that a
	 * friendly GUI is displayed with more descriptive text.
	 */
	public static class SudoAskPassGuiUser extends AbstractProcessBuilderEffectiveUser implements PlatformElevation {
		private Optional<String> username;

		/**
		 * Constructor for specific user
		 * 
		 * @param username username
		 */
		SudoAskPassGuiUser(Optional<String> username) {
			this.username = username;
		}

		@Override
		public void lower() {
		}

		@Override
		public void elevate(ProcessBuilder builder) {
			createTempScript(javaAskPassScript(AskPass.class));
			var cmd = builder.command();
			cmd.add(0, "sudo");
			cmd.add(1, "-A");
			cmd.add(2, "-E");
			username.ifPresent(u -> {
				cmd.add(3, "-u");
				cmd.add(4, u);
			});
			builder.environment().put("SUDO_ASKPASS", tempScript.getAbsolutePath());
		}
	}

	public static class SudoAskPassUser extends AbstractProcessBuilderEffectiveUser implements PlatformElevation {
		private Optional<String> username;

		SudoAskPassUser(Optional<String> username) {
			this.username = username;
		}

		@Override
		public void lower() {
		}

		@Override
		public void elevate(ProcessBuilder builder) {
			createTempScript(javaAskPassScript(AskPassConsole.class));
			var cmd = builder.command();
			cmd.add(0, "sudo");
			cmd.add(1, "-A");
			cmd.add(2, "-E");
			username.ifPresent(u -> {
				cmd.add(3, "-u");
				cmd.add(4, u);
			});
			builder.environment().put("SUDO_ASKPASS", tempScript.getAbsolutePath());
		}
	}

	/**
	 * A {@link PlatformElevation} that uses that 'su' command to raise privileges
	 * to administrator.
	 *
	 */
	public static class SUAdministrator extends AbstractProcessBuilderEffectiveUser implements PlatformElevation {

		@Override
		public void lower() {
		}

		@Override
		public void elevate(ProcessBuilder builder) {
			if (hasCommand("sudo")) {
				/*
				 * This is the only thing we can do to determine if to use sudo or not.
				 * /etc/shadow could not always be read to determine if root has a password
				 * which might be a hint. Neither could /etc/sudoers
				 */
				builder.command().add(0, "sudo");
			} else {
				if (System.console() == null)
					throw new IllegalStateException("This program requires elevated privileges, "
							+ "but sudo is not available, and the fallback 'su' is not capable of "
							+ "running without a controlling terminal.");
				var cmd = builder.command();
				var bui = getQuotedCommandString(cmd);
				cmd.clear();
				cmd.add("su");
				cmd.add("-c");
				cmd.add(bui.toString());
			}
		}
	}

	static StringBuilder getQuotedCommandString(List<String> cmd) {
		return getQuotedCommandString(cmd, '\'');
	}

	static StringBuilder getQuotedCommandString(List<String> cmd, char qu) {
		StringBuilder bui = new StringBuilder();
		for (int i = 0; i < cmd.size(); i++) {
			if (bui.length() > 0) {
				bui.append(' ');
			}
			var str = escapeSingleQuotes(cmd.get(i));
			if (str.contains(" "))
				bui.append(qu);
			bui.append(str);
			if (str.contains(" "))
				bui.append(qu);
		}
		return bui;
	}

	static String escapeSingleQuotes(String src) {
		return src.replace("'", "''");
	}
}
