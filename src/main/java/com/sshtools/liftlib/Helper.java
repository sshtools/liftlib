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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.concurrent.Callable;

public class Helper implements Callable<Integer> {

	public static void main(String[] args) throws Exception {
		System.exit(new Helper().call());
	}

	public Helper() {
	}

	@Override
	public Integer call() throws Exception {
		var helperPath = System.getProperty("liftlib.socket", System.getenv("LIFTLIB_SOCKET"));
		if (helperPath == null) {
			System.setOut(System.err);
			System.setIn(InputStream.nullInputStream());
			try (var in = new ObjectInputStream(System.in)) {
				try (var out = new ObjectOutputStream(System.out)) {
					cmdLoop(in, out);
				}
			} catch (EOFException e) {
			}
		} else {
			var socketPath = Path.of(helperPath);
			var socketAddress = UnixDomainSocketAddress.of(socketPath);
			var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
			channel.connect(socketAddress);
			try (var in = new ObjectInputStream(Channels.newInputStream(channel))) {
				try (var out = new ObjectOutputStream(Channels.newOutputStream(channel))) {
					cmdLoop(in, out);
				}
			} catch (EOFException e) {
			}
		}
		return 0;
	}

	private void cmdLoop(ObjectInputStream in, ObjectOutputStream out) throws IOException, ClassNotFoundException {
		while (true) {
			@SuppressWarnings("unchecked")
			var closure = (ElevatedClosure<Serializable>) in.readObject();
			if (closure == null)
				return;
			try {
				var result = closure.call();
				out.writeBoolean(true);
				out.writeObject(result);
			} catch (Throwable t) {
				out.writeBoolean(false);
				out.writeObject(t);
			} finally {
				out.flush();
			}
		}
	}

}
