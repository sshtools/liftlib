package com.sshtools.liftlib;

import java.io.Serializable;
import java.util.concurrent.Callable;

@FunctionalInterface
public interface ElevatedClosure<S extends Serializable> extends Callable<S>, Serializable {

}
