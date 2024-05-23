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
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sshtools.liftlib.impl.ElevatedJVM;
import com.sshtools.liftlib.impl.PlatformElevation;

public final class Elevator {

	private final static Logger LOG = Logger.getLogger(Elevator.class.getSimpleName());
	
	public final static class DefaultElevator {
		private static Elevator DEFAULT;
		
		static {
			var builder = new Elevator.ElevatorBuilder();
			builder.withoutFailOnCancel();
			builder.withReauthorizationPolicy(ReauthorizationPolicy.NEVER);
			DEFAULT = builder.build();
		}
	}
	
	public static Elevator elevator() {
		return DefaultElevator.DEFAULT;
	}

	public enum ReauthorizationPolicy {
		EVERY_TIME, NEVER, INTERVAL;
	}
	
	public static interface Run extends ElevatedClosure<Serializable, Serializable> {

		@Override
		default Serializable call(ElevatedClosure<Serializable, Serializable> proxy) throws Exception {
			run();
			return null;
		}
		
		void run() throws Exception;
		
		
	}
	
	public static interface Call<RET extends Serializable> extends ElevatedClosure<RET, Serializable> {

		@Override
		default RET call(ElevatedClosure<RET, Serializable> proxy) throws Exception {
			return call();
		}
		
		RET call() throws Exception;
		
	}
	
	public final static class ElevatorBuilder {

		private boolean failOnCancel = true;
		private ReauthorizationPolicy reauthorizationPolicy = ReauthorizationPolicy.EVERY_TIME;
		private Duration reauthorizationInterval = Duration.ofMinutes(1);
		private Optional<String> username = Optional.empty();
		private Optional<char[]> password = Optional.empty();
		private Optional<Boolean> devMode = Optional.empty();
		private Optional<Supplier<RPC>> rpc = Optional.empty();
		private List<RuntimePathProvider> pathProviders = new ArrayList<>();
		private boolean forceClassPath = Boolean.getBoolean("liftlib.forceClassPath");

		public Elevator build() {
			return new Elevator(this);
		}
		
		public ElevatorBuilder withForceClassPath(boolean forceClassPath) {
			this.forceClassPath = forceClassPath;
			return this;
		}
		
		public ElevatorBuilder withRPC(Supplier<RPC> rpc) {
			this.rpc = Optional.of(rpc);
			return this;
		}
		
		public ElevatorBuilder withRuntimePathProviders(RuntimePathProvider... providers) {
			return withRuntimePathProviders(Arrays.asList(providers));
		}
		
		public ElevatorBuilder withRuntimePathProviders(Collection<RuntimePathProvider> providers) {
			this.pathProviders.addAll(providers);
			return this;
		}
		
		public ElevatorBuilder withDevMode(boolean devMode) {
			this.devMode = Optional.of(devMode);
			return this;
		}

		public ElevatorBuilder withReauthorizationPolicy(ReauthorizationPolicy reauthorizationPolicy) {
			this.reauthorizationPolicy = reauthorizationPolicy;
			;
			return this;
		}

		public ElevatorBuilder withReauthorizationInterval(Duration reauthorizationInterval) {
			this.reauthorizationInterval = reauthorizationInterval;
			return this;
		}

		public ElevatorBuilder withoutFailOnCancel() {
			failOnCancel = false;
			return this;
		}

		public ElevatorBuilder withFailOnCancel(boolean failOnCancel) {
			this.failOnCancel = failOnCancel;
			return this;
		}
		
		public ElevatorBuilder withUsername(String username) {
			return withUsername(Optional.ofNullable(username));
		}

		public ElevatorBuilder withUsername(Optional<String> username) {
			this.username = username;
			return this;
		}
		
		public ElevatorBuilder withPassword(char[] password) {
			return withPassword(Optional.ofNullable(password));
		}
		
		public ElevatorBuilder withPasswordString(String password) {
			return withPasswordString(Optional.ofNullable(password));
		}

		public ElevatorBuilder withPassword(Optional<char[]> password) {
			this.password = password;
			return this;
		}

		public ElevatorBuilder withPasswordString(Optional<String> password) {
			this.password = password.map(p -> p.toCharArray());
			return this;
		}

	}

	private final Object lock = new Object();
	private final boolean failOnCancel;
	private final ReauthorizationPolicy reauthorizationPolicy;
	private final Duration reauthorizationInterval;
	private final Optional<String> username;
	private final Optional<char[]> password;
	private final Optional<Boolean> devMode;
	private final List<RuntimePathProvider> pathProviders;
	private final Optional<Supplier<RPC>> rpc;
	private final boolean forceClassPath;
	
	private ElevatedJVM jvm;
	private long lastAuth;
	private ObjectOutputStream out;
	private ObjectInputStream in;

	Elevator(ElevatorBuilder builder) {
		this.failOnCancel = builder.failOnCancel;
		this.reauthorizationPolicy = builder.reauthorizationPolicy;
		this.reauthorizationInterval = builder.reauthorizationInterval;
		this.username = builder.username;
		this.password = builder.password;
		this.devMode = builder.devMode;
		this.pathProviders = Collections.unmodifiableList(builder.pathProviders.isEmpty() ? Arrays.asList(BootRuntimePathProvider.getDefault()) : builder.pathProviders);
		this.rpc = builder.rpc;
		this.forceClassPath = builder.forceClassPath;
	}
	
	public void run(Run closure) throws Exception {
		closure(closure);
	}
	
	public void runUnchecked(Run closure) {
		try {
			closure(closure);
		} catch(RuntimeException re) {
			throw re;
		}  catch (Exception e) {
			throw new IllegalStateException("Failed to execute elevated closure.", e);
		}
	}
	
	public <RET extends Serializable> RET call(Call<RET> closure) throws Exception {
		return closure(closure);
	}
	
	public <RET extends Serializable> RET callUnchecked(Call<RET> closure)  {
		try {
			return closure(closure);
		}catch(RuntimeException re) {
			throw re;
		}  catch (Exception e) {
			throw new IllegalStateException("Failed to execute elevated closure.", e);
		}
	}

	@SuppressWarnings("unchecked")
	public <S extends Serializable, E extends Serializable> S closure(ElevatedClosure<S, E> closure) throws Exception {
		synchronized (lock) {
			if (jvm != null && lastAuth > 0 && reauthorizationPolicy == ReauthorizationPolicy.INTERVAL
					&& System.currentTimeMillis() > lastAuth + reauthorizationInterval.toMillis()) {
				if(LOG.isLoggable(Level.FINE))
					LOG.fine("Elevator JVM timed-out");
				closeJvm();
			}
			try {
				if (jvm == null || !jvm.isActive()) {
					if(LOG.isLoggable(Level.FINE))
						LOG.fine("Creating new elevator JVM");
					jvm = new ElevatedJVM(PlatformElevation.forEnvironment(username, password), devMode.orElseGet(() -> Files.exists(Paths.get("pom.xml"))), pathProviders, rpc.orElse(() -> RPC.get()), forceClassPath);
					out = new ObjectOutputStream(jvm.getOutputStream());
				}
				if(LOG.isLoggable(Level.FINE))
					LOG.fine("Sending closure");
				out.writeObject(closure);				
				out.flush();
				if(LOG.isLoggable(Level.FINE))
					LOG.fine("Sent closure");
				if(in == null) {
					in = new ObjectInputStream(jvm.getInputStream());
					if(LOG.isLoggable(Level.FINE))
						LOG.fine("Opened new input stream");
				}
				while(true) {
				    
					if(LOG.isLoggable(Level.FINE))
						LOG.fine("Awating command");
					
				    var cmd = in.readInt();
				    
					if(LOG.isLoggable(Level.FINE))
						LOG.fine(MessageFormat.format("Got command {0}", cmd));
					
				    if(cmd == Helper.RESP_COMPLETE) {
					    
						if(LOG.isLoggable(Level.FINE))
							LOG.fine("Completed command");
						
				        var ok = in.readBoolean();
		                if (ok) {
							if(LOG.isLoggable(Level.FINE))
								LOG.fine("Received OK, waiting for return object.");
		                    var res = (S) in.readObject();

					        if(LOG.isLoggable(Level.FINE))
								LOG.fine(MessageFormat.format("Response object: {0}", String.valueOf(res)));
					        
							return res;
		                }
		                else {
							if(LOG.isLoggable(Level.FINE))
								LOG.fine("Received ERR, waiting for exception.");
							
		                    var t = (Throwable) in.readObject();
		                    if(LOG.isLoggable(Level.FINE))
								LOG.fine(MessageFormat.format("Exception object: {0}", String.valueOf(t)));
		                    
		                    if(t instanceof RuntimeException)
		                    	throw (RuntimeException)t;
		                    else if(t instanceof Exception)
		                    	throw (Exception)t;  
		                    else
		                    	throw new Exception(t);
		                }
		                
				    }
				    else if(cmd == Helper.RESP_EVENT) {
					    
						if(LOG.isLoggable(Level.FINE))
							LOG.fine("Event command");
						
				        E evt = (E) in.readObject();
				        
				        if(LOG.isLoggable(Level.FINE))
							LOG.fine(MessageFormat.format("Event object: {0}", String.valueOf(evt)));
				        
						closure.event(evt);
				    }
				    else
				        throw new IOException("Unexpected response command. " + cmd);
				}
			} catch (EOFException e) {
				if (failOnCancel)
					throw e;
				else
					return null;
			} finally {
				lastAuth = System.currentTimeMillis();
				if (reauthorizationPolicy == ReauthorizationPolicy.EVERY_TIME) {
					closeJvm();
				}
			}
		}
	}

	private void closeJvm() throws IOException {
		try {
		    if(jvm != null) {
    			if(LOG.isLoggable(Level.FINE))
    				LOG.fine("Closing Elevator JVM");
    			
    			jvm.close();
		    }
		} finally {
			if(LOG.isLoggable(Level.FINE))
				LOG.fine("Closed Elevator JVM");
			
			jvm = null;
			in = null;
			out = null;
		}
	}

	public void close() {
		if(jvm != null)
			try {
				closeJvm();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		
	}

}
