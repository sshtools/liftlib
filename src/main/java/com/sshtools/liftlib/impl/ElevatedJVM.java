package com.sshtools.liftlib.impl;

import static com.sshtools.liftlib.OS.expandModulePath;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import com.sshtools.liftlib.Helper;
import com.sshtools.liftlib.OS;

public class ElevatedJVM implements Closeable {

	private final Process process; 
	private boolean closed;
	private final PlatformElevation elevation;
	private final Semaphore lock = new Semaphore(1);
	private final Path socketPath;
	private final List<Path> removeFilesOnClose = new ArrayList<>();

	private SocketChannel channel;
	private InputStream input;
	private OutputStream output;
	private boolean ready;
	private Thread thread;;

	public ElevatedJVM(PlatformElevation elevation) throws IOException {
		this.elevation = elevation;

		var vargs = new ArrayList<String>();

		vargs.add(OS.getJavaPath());

		var cp = System.getProperty("java.class.path");
		var mp = expandModulePath(System.getProperty("jdk.module.path"));
		if (mp != null) {
			if (cp == null || cp.equals(""))
				cp = mp;
			else
				cp = cp + File.pathSeparator + mp;
		}

		if (cp != null && cp.length() > 0) {
			if(OS.isMacOs() && Files.exists(Paths.get("pom.xml"))) {
				/* Argh. Work around for Mac OS and it's very restrictive permissions
				 * system. As an administrator, even we can't read certain files
				 * (without consent), but consent can never be given. 
				 * 
				 * https://eclecticlight.co/2020/02/15/why-privileged-commands-may-never-be-allowed/
				 * is about the closest to some kind of explanation for this.
				 */
				var tmpPath = Paths.get("/tmp/liftlib/" + Integer.toUnsignedLong(hashCode()) + ".tmp");
				Files.createDirectories(tmpPath);
				int idx = 0;
				for(var cpEl : cp.split(File.pathSeparator)) {
					var path = Paths.get(cpEl);
					var target = tmpPath.resolve(path.getFileName());
					if(Files.isDirectory(path)) {
						target = tmpPath.resolve("dir" + (idx ++));
						Files.createDirectories(target);
						OS.copy(path, target);
					}
					else {
						Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
					}
					removeFilesOnClose.add(target);
					target.toFile().deleteOnExit();
				}
				cp = String.join(File.pathSeparator, removeFilesOnClose.stream().map(Path::toString).collect(Collectors.toList()));
				removeFilesOnClose.add(tmpPath);
				tmpPath.toFile().deleteOnExit();
			}
			
			vargs.add("--class-path");
			vargs.add(cp);
		}

		socketPath = Files.createTempFile("elv", ".socket");
		socketPath.toFile().deleteOnExit();
		socketPath.toFile().setWritable(true, false);
		socketPath.toFile().setReadable(true, false);
		Files.deleteIfExists(socketPath);
		var socketAddress = UnixDomainSocketAddress.of(socketPath);

		var p = new LinkedHashSet<String>();
		if(!OS.isWindows()) {
			/* TODO passing on this on Windows prevents execution as there
			 * is some issue with escaping spaces */
			p.addAll(Arrays.asList("java.library.path", "jna.library.path", "java.security.policy"));
		}
		for (var s : p) {
			if (System.getProperty(s) != null) {
				vargs.add("-D" + s + "=" + System.getProperty(s));
			}
		}
		vargs.add("-Dliftlib.socket=" + socketPath.toString()); // more visible but should always work
		vargs.add(Helper.class.getName());
		
		var serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		serverChannel.bind(socketAddress);

		var builder = new ProcessBuilder(vargs);
		
		if(OS.isMacOs()) {
			/* Another Mac OS X hack. If you try to run GUI applications in the 
			 * elevated helper, it doesn't detect the Aqua session. We don't actually
			 * need this for the helper, only anything spawned from it (e.g. an uninstaller / updater
			 * in Jaul toolbox)
			 */
			builder.environment().put("AWT_FORCE_HEADFUL", "true");
		}
		
		builder.environment().put("LIFTLIB_SOCKET", socketPath.toString()); // likely wont get passed on (e.g pkexec)
		builder.redirectError(Redirect.INHERIT);
		builder.redirectOutput(Redirect.INHERIT);
		builder.redirectInput(Redirect.INHERIT);

		elevation.elevate(builder);
		
		process = builder.start();  // todo temp
		try {
			lock.acquire();
		} catch (InterruptedException e) {
			throw new IOException("Interrupted.", e);
		}
		thread = new Thread(() -> {
			try {
				channel = serverChannel.accept();
				input = Channels.newInputStream(channel);
				output = Channels.newOutputStream(channel);
				ready = true;
				lock.release();
			} catch (IOException ioe) {
				if (!closed)
					ioe.printStackTrace();
			}
		}, "ElevationChannel");
		thread.start();
		while (thread.isAlive()) {
			try {
				thread.join(1000);
			} catch (InterruptedException e) {
				throw new IOException("Interrupted.", e);
			}
			if (!isActive()) {
				close();
				throw new EOFException("Failed to elevate, exit value " + process.exitValue());
			} else if (ready) {
				break;
			}
		}
	}

	public boolean isActive() {
		return process.isAlive();
	}

	public InputStream getInputStream() {
		if (closed)
			throw new IllegalStateException("Elevated JVM is already closed.");
		if (!ready) {
			try {
				lock.acquire();
			} catch (InterruptedException e) {
				throw new IllegalStateException("Interrupted.", e);
			}
		}
		return input;
	}

	public OutputStream getOutputStream() {
		if (closed)
			throw new IllegalStateException("Elevated JVM is already closed.");
		if (!ready) {
			try {
				lock.acquire();
			} catch (InterruptedException e) {
				throw new IllegalStateException("Interrupted.", e);
			}
		}
		return output;
	}

	@Override
	public void close() throws IOException {
		if (!closed) {
			closed = true;
			if (thread != null) {
				thread.interrupt();
			}
			try {
				process.getInputStream().close();
			} finally {
				try {
					process.getOutputStream().close();
				} finally {
					try {
						if (channel != null)
							channel.close();
					} finally {
						try {
							process.waitFor();
						} catch (InterruptedException e) {
							throw new IOException("Interrupted.", e);
						} finally {
							try {
								elevation.lower();
							}
							finally {
								try {
									Files.delete(socketPath);
								}
								finally {
									for(var p : removeFilesOnClose) {
										try {
											Files.delete(p);
										}
										catch(Exception e) {}
									}
								}
							}
						}
					}
				}
			}
		}
	}

}
