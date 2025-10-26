package com.sshtools.liftlib;

import java.io.Closeable;
import java.io.Serializable;

import com.sshtools.liftlib.Elevator.Call;
import com.sshtools.liftlib.Elevator.Run;

public interface IElevator extends Closeable {

	default void run(Run closure) throws Exception {
		closure(closure);
	}

	default void runUnchecked(Run closure) {
		try {
			closure(closure);
		} catch(RuntimeException re) {
			throw re;
		}  catch (Exception e) {
			throw new IllegalStateException("Failed to execute elevated closure.", e);
		}
	}
	
	default <RET extends Serializable> RET call(Call<RET> closure) throws Exception {
		return closure(closure);
	}

	default <RET extends Serializable> RET callUnchecked(Call<RET> closure)  {
		try {
			return closure(closure);
		}catch(RuntimeException re) {
			throw re;
		}  catch (Exception e) {
			throw new IllegalStateException("Failed to execute elevated closure.", e);
		}
	}

	<S extends Serializable, E extends Serializable> S closure(ElevatedClosure<S, E> closure) throws Exception;

	@Override
	void close();

}