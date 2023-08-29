package com.sshtools.liftlib.impl;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sshtools.liftlib.RPC;

public class UDSRPC implements RPC {

	@Override
	public SocketChannel connect(String helperPath) throws IOException {
		var socketPath = Path.of(helperPath);
		var socketAddress = UnixDomainSocketAddress.of(socketPath);
		var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
		channel.connect(socketAddress);
		return channel;
	}

	@Override
	public Endpoint endpoint() throws IOException {
        var socketPath = Files.createTempFile("elv", ".socket");
        socketPath.toFile().deleteOnExit();
        socketPath.toFile().setWritable(true, false);
        socketPath.toFile().setReadable(true, false);
        Files.deleteIfExists(socketPath);
        
        var socketAddress = UnixDomainSocketAddress.of(socketPath);
		var serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		serverChannel.bind(socketAddress);
		return new RPC.Endpoint() {
			
			@Override
			public void close() throws IOException {
				try {
					serverChannel.close();
				}
				finally {
					Files.delete(socketPath);
				}
			}
			
			@Override
			public String uri() {
				return socketPath.toString();
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
