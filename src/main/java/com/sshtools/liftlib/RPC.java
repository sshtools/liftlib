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

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ServiceLoader;

import com.sshtools.liftlib.impl.TCPRPC;

public interface RPC {
	
	public interface Endpoint extends Closeable {
		SocketAddress address();
		
		String uri();

		SocketChannel accept() throws IOException;
	}

	public static RPC get() {
		return ServiceLoader.load(RPC.class).stream().map(f -> f.get())
				.sorted((o1, o2) -> Integer.valueOf(o1.weight()).compareTo(o2.weight())).findFirst()
				.orElseGet(() -> new TCPRPC());
	}
	
	public static RPC get(ClassLoader cl) {
		return ServiceLoader.load(RPC.class, cl).stream().map(f -> f.get())
				.sorted((o1, o2) -> Integer.valueOf(o1.weight()).compareTo(o2.weight())).findFirst()
				.orElseGet(() -> new TCPRPC());
	}
	
	int weight();

	Endpoint endpoint() throws IOException;

	SocketChannel connect(String helperPath) throws IOException;
}
