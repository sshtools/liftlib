package com.sshtools.liftlib;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ServiceLoader;

public interface RPC {
	
	public interface Endpoint extends Closeable {
		SocketAddress address();
		
		String uri();

		SocketChannel accept() throws IOException;
	}
	
	public static RPC get() {
		return ServiceLoader.load(RPC.class).stream().map(f -> f.get()).findFirst().orElseThrow(() -> new IllegalStateException("No RPC providers."));
	}

	Endpoint endpoint() throws IOException;

	SocketChannel connect(String helperPath) throws IOException;
}
