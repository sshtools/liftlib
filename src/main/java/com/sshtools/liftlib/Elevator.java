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
import java.time.Duration;
import java.util.Optional;

import com.sshtools.liftlib.impl.ElevatedJVM;
import com.sshtools.liftlib.impl.PlatformElevation;

public final class Elevator {
	
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

		public Elevator build() {
			return new Elevator(this);
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
				closeJvm();
			}
			try {
				if (jvm == null || !jvm.isActive()) {
					jvm = new ElevatedJVM(PlatformElevation.forEnvironment(username, password));
					out = new ObjectOutputStream(jvm.getOutputStream());
				}
				out.writeObject(closure);
				out.flush();
				if(in == null) {
					in = new ObjectInputStream(jvm.getInputStream());
				}
				while(true) {
				    var cmd = in.readInt();
				    if(cmd == Helper.RESP_COMPLETE) {
				        var ok = in.readBoolean();
		                if (ok)
		                    return (S) in.readObject();
		                else {
		                    var t = (Throwable) in.readObject();
		                    if(t instanceof RuntimeException)
		                    	throw (RuntimeException)t;
		                    else if(t instanceof Exception)
		                    	throw (Exception)t;  
		                    else
		                    	throw new Exception(t);
		                }
		                
				    }
				    else if(cmd == Helper.RESP_EVENT) {
				        closure.event((E) in.readObject());
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
			jvm.close();
		} finally {
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
