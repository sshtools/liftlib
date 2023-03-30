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
