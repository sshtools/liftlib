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
package com.sshtools.liftlib.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Simple console based helper application that asks for a password (input on
 * stdin, message on stderr) and prints it on stdout.
 */
public class AskPassConsole {

	/**
	 * Entry point.
	 * 
	 * @param args
	 *            command line arguments
	 * @throws Exception
	 *             on any error
	 */
	public static void main(String[] args) throws Exception {
		var console = System.console();
		if (console == null)
			System.err.println("WARNING: Not on a console, password will be visible");

		// Title
		var title = System.getenv("ASKPASS_TITLE");
		if (title == null) {
			title = "Administrator Password Required";
		}

		// Text
		var text = System.getenv("ASKPASS_TEXT");
		if (text == null) {
			text = "This application requires elevated privileges. Please\n";
			text += "enter the administrator password to continue.";
		}

		System.err.println(title);
		System.err.println();
		System.err.println(text);
		System.err.println();

		System.err.print("Enter a password:");
		String pw = null;
		if (console == null) {
			var br = new BufferedReader(new InputStreamReader(System.in));
			pw = br.readLine();
		} else {
			var c = console.readPassword("");
			pw = c == null ? null : new String(c);
		}
		if (pw != null)
			System.out.println(pw);
	}
}
