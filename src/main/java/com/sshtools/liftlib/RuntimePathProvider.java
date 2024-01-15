package com.sshtools.liftlib;

import java.util.Properties;
import java.util.Set;

public interface RuntimePathProvider {

	void fill(Set<String> legacyClassPath, Set<String> modulePath, Properties systemProperties);
}
