package com.sshtools.liftlib;

import java.io.File;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;

public class BootRuntimePathProvider implements RuntimePathProvider {

	private final static class Default {
		private final static RuntimePathProvider DEFAULT = new BootRuntimePathProvider();
	}

	private BootRuntimePathProvider() {
	}

	public final static RuntimePathProvider getDefault() {
		return Default.DEFAULT;
	}

	@Override
	public void fill(Set<String> legacyClassPath, Set<String> modulePath, Properties systemProperties) {
		var mp = System.getProperty("jdk.module.path");
		if (mp != null && mp.length() > 0) {
			modulePath.addAll(Arrays.asList(mp.split(File.pathSeparator)));
		}

		var cp = System.getProperty("java.class.path");
		if (cp != null && cp.length() > 0) {
			legacyClassPath.addAll(Arrays.asList(cp.split(File.pathSeparator)));
		}

		addSysPropIfExists(systemProperties, "file.encoding");
		if (!OS.isWindows()) {
			/*
			 * TODO passing on this on Windows prevents execution as there is some issue
			 * with escaping spaces
			 */
			addSysPropIfExists(systemProperties, "java.library.path");
			addSysPropIfExists(systemProperties, "jna.library.path");
			addSysPropIfExists(systemProperties, "java.security.policy");
		}
	}

	void addSysPropIfExists(Properties target, String name) {
		var val = System.getProperty(name);
		if (val != null) {
			target.setProperty(name, val);
		}
	}

}
