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
package com.sshtools.liftlib.impl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import com.sshtools.liftlib.RPC;

public class TCPRPC implements RPC {

	@Override
	public SocketChannel connect(String helperPath) throws IOException {
		var socketAddress = new InetSocketAddress("127.0.0.1", Integer.parseInt(helperPath));
		var channel = SocketChannel.open();
		channel.connect(socketAddress);
		return channel;
	}

	@Override
	public Endpoint endpoint() throws IOException {
        var socketAddress = new InetSocketAddress("127.0.0.1", 0);
		var serverChannel = ServerSocketChannel.open();
		serverChannel.bind(socketAddress);
		return new RPC.Endpoint() {
			
			@Override
			public void close() throws IOException {
				serverChannel.close();
			}
			
			@Override
			public String uri() {
				try {
					return String.valueOf(((InetSocketAddress)serverChannel.getLocalAddress()).getPort());
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			
			@Override
			public SocketAddress address() {
				return socketAddress;
			}

			@Override
			public SocketChannel accept() throws IOException {
				return serverChannel.accept();
			}

		};
	}
}
