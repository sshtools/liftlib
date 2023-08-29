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
