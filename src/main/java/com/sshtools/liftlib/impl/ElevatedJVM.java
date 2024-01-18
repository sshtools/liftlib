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

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.sshtools.liftlib.Helper;
import com.sshtools.liftlib.OS;
import com.sshtools.liftlib.OS.Desktop;
import com.sshtools.liftlib.RPC;
import com.sshtools.liftlib.RPC.Endpoint;
import com.sshtools.liftlib.RuntimePathProvider;

public class ElevatedJVM implements Closeable {
	
	final static Logger LOG = Logger.getLogger(ElevatedJVM.class.getSimpleName());

	private final Process process; 
	private boolean closed;
	private final PlatformElevation elevation;
	private final Semaphore lock = new Semaphore(1);
	private final Endpoint endpoint;
	private final List<Path> removeFilesOnClose = new ArrayList<>();

	private SocketChannel channel;
	private InputStream input;
	private OutputStream output;
	private boolean ready;
	private Thread thread;;

	public ElevatedJVM(PlatformElevation elevation, boolean dev, List<RuntimePathProvider> pathProviders, Supplier<RPC> rpcSupplier, boolean forceClassPath) throws IOException {
		
		this.elevation = elevation;

		var vargs = new ArrayList<String>();
		var modular = false;
		var rpc = rpcSupplier.get();
		var nativeImage = OS.isNativeImage();
		var macDev = !nativeImage && OS.isMacOs() && dev;
		var sharedLibrary = OS.isSharedLibrary();
		
		endpoint = rpc.endpoint();
		
		if(sharedLibrary) {
			throw new IOException("Elevation is not supported in shared libraries, the calling application must be run as administrator or perform its own elevation.");
		}
		else if(nativeImage) {
	        LOG.info("In native image, elevating this executable");
	        vargs.add(Paths.get(ProcessHandle.current().info().command().get()).toAbsolutePath().toString());
		    vargs.add("--elevate");
            vargs.add(endpoint.uri());
		}
		else {
			LOG.info("In interpreted mode, starting new elevated JVM");

    		vargs.add(OS.getJavaPath());
    		
    		if(Boolean.getBoolean("liftlib.debug")) {
    			vargs.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + System.getProperty("liftlib.debugPort", "8000"));
    		}
    		var idx = new AtomicInteger(0);
    
    		Set<String> mp = new LinkedHashSet<String>();
    		Set<String> cp = new LinkedHashSet<String>();
    		var sp = new Properties();
    		for(var prov : pathProviders) {
    			prov.fill(cp, mp, sp);
    		}
    		
    		
    		if (!mp.isEmpty()) {
    			if(forceClassPath) {
	    			for(var p : mp) {
	    				var f = Paths.get(p);
    					if(Files.isDirectory(f)) {
    						int jars = 0;
    						try(var dstream = Files.newDirectoryStream(f, fp -> fp.getFileName().toString().endsWith(".jar"))) {
    							for(var d : dstream) {
    								cp.add(f.resolve(d).toString());
    								jars++;
    							}
    						}
    						if(jars ==0) {
        						cp.add(p);
    						}
    					}
    					else {
    						cp.add(p);
    					}
	    			}
    			}
    			else {
	    			for(var p : mp) {
	    				if(!modular) {
	    					var f = Paths.get(p);
	    					if(Files.isDirectory(f)) {
	    						try(var dstream = Files.newDirectoryStream(f)) {
	    							for(var d : dstream) {
	    								modular = isLiftLib(d);
	    								if(modular) {
	    									break;
	    								}
	    							}
	    						}
	    					}
	    					else {
	    						modular = isLiftLib(f);
	    						if(modular)
	    							break;
	    					}
	    				}
	    			}
	    			if(macDev) {
	    				mp = fixMacClassDevelopmentPath(mp, idx);
	    			}
	    			vargs.add("-p");
	    			vargs.add(makePathsAbsolute(mp));
    			}
    		}
    
    		if (!cp.isEmpty()) {
    			if(macDev) {
    				cp = fixMacClassDevelopmentPath(cp, idx);
    			}
    			
    			vargs.add("-classpath");
    			vargs.add(makePathsAbsolute(cp));
    		}

    		for(var ent : sp.entrySet()) {
                vargs.add("-D" + ent.getKey() + "=" + ent.getValue());
    		}

    		vargs.add("-Dliftlib.rpc=" + rpc.getClass().getName());
            vargs.add("-Dliftlib.socket=" + endpoint.uri()); // more visible but should always work
            if(modular) {
                /* TODO Use ProcessHandler to get the full original command line and process that instead.
                 *  This means it will have to be able to properly pass all java command arguments 
                 *  
                 *  NOTE: ProcessHandle doesnt work properly on Windows and never has!
                 */
                vargs.add("--add-modules");
                vargs.add("ALL-MODULE-PATH");
                
                vargs.add("-m");
                vargs.add("com.sshtools.liftlib/" + Helper.class.getName());
            }
            else {
                vargs.add(Helper.class.getName());
            }
		}

		var builder = new ProcessBuilder(vargs);
		
		if(OS.isMacOs() && !OS.getDesktopEnvironment().equals(Desktop.CONSOLE)) {
			/* Another Mac OS X hack. If you try to run GUI applications in the 
			 * elevated helper, it doesn't detect the Aqua session. We don't actually
			 * need this for the helper, only anything spawned from it (e.g. an uninstaller / updater
			 * in Jaul toolbox)
			 */
			builder.environment().put("AWT_FORCE_HEADFUL", "true");
		}
		
		builder.environment().put("LIFTLIB_SOCKET", endpoint.uri()); // likely wont get passed on (e.g pkexec)
		builder.redirectError(Redirect.INHERIT);
		builder.redirectOutput(Redirect.INHERIT);
		builder.redirectInput(Redirect.INHERIT);
		
		if(macDev) {
			var tmpPath = Paths.get("/tmp/liftlib/" + Integer.toUnsignedLong(hashCode()) + ".tmp");
		    builder.directory(tmpPath.toFile());
		}

		LOG.log(Level.INFO, "Helper Command: {0}, Elevator: {1}", new Object[] { String.join(" ", builder.command()), elevation.getClass().getName() });
		elevation.elevate(builder);

		LOG.log(Level.INFO, "Elevator Command: {0}", String.join(" ", builder.command()));
		
		process = builder.start();  // todo temp
		try {
			lock.acquire();
		} catch (InterruptedException e) {
			throw new IOException("Interrupted.", e);
		}
		thread = new Thread(() -> {
			try {
				LOG.log(Level.INFO, "Waiting for connection from helper");
				channel = endpoint.accept();
				LOG.log(Level.INFO, "Got connection from helper");
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
		LOG.log(Level.INFO, "Waiting for helper to exit");
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
		elevation.ready();
		LOG.log(Level.INFO, "Helper exited cleanly.");
	}

	private String makePathsAbsolute(Set<String> mp) {
	    var l = new ArrayList<String>();
	    for(var e : mp) {
	        var p = Paths.get(e);
	        if(p.isAbsolute())
	            l.add(p.toString());
	        else 
                l.add(p.toAbsolutePath().toString());
	    }
        return String.join(File.pathSeparator, l);
    }

    private boolean isLiftLib(Path d) {
		var fn = d.getFileName().toString();
		if((fn.startsWith("liftlib") && fn.endsWith(".jar") ) || (d.toString().replace('\\', '/').contains("liftlib/target/classes"))) {
			return true;
		}
		return false;
	}

	private Set<String> fixMacClassDevelopmentPath(Set<String> cp, AtomicInteger idx) throws IOException {
		/* Argh. Work around for Mac OS and it's very restrictive permissions
		 * system. As an administrator, even we can't read certain files
		 * (without consent), but consent can never be given. 
		 * 
		 * https://eclecticlight.co/2020/02/15/why-privileged-commands-may-never-be-allowed/
		 * is about the closest to some kind of explanation for this.
		 */
		var tmpPath = Paths.get("/tmp/liftlib/" + Integer.toUnsignedLong(hashCode()) + ".tmp");
		Files.createDirectories(tmpPath);
		var newPaths = new ArrayList<Path>();
		for(var cpEl : cp) {
			var path = Paths.get(cpEl);
			if(Files.isRegularFile(path) && cpEl.toLowerCase().endsWith(".jar")) {
				var target = tmpPath.resolve(path.getFileName());
				Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
				removeFilesOnClose.add(target);
				target.toFile().deleteOnExit();
				newPaths.add(target);
			}
			else if(Files.isDirectory(path)) {
				var target = tmpPath.resolve("dir" + (idx.getAndIncrement()));
				Files.createDirectories(target);
				OS.copy(path, target);
				removeFilesOnClose.add(target);
				target.toFile().deleteOnExit();
				newPaths.add(target);
			}
			else {
				newPaths.add(path);
			}
		}
		cp = newPaths.stream().map(Path::toString).collect(Collectors.toSet());
		removeFilesOnClose.add(tmpPath);
		tmpPath.toFile().deleteOnExit();
		return cp;
	}

	public boolean isActive() {
		if(OS.isWindows())
			return !closed;
		else
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
			LOG.info("Closing elevated JVM");
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
									endpoint.close();
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
