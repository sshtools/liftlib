# LiftLib

![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.sshtools/liftlib/badge.svg)
[![javadoc](https://javadoc.io/badge2/com.sshtools/liftlib/javadoc.svg)]
(https://javadoc.io/doc/com.sshtools/liftlib)![JPMS](https://img.shields.io/badge/JPMS-com.sshtools.liftlib-purple) 

A small, no dependency Java library whose only job is to create an *Elevated JVM* for running small blocks of Java code as a system administrator. The user will be prompted to accept the elevation.

The code must be wrapped in an `ElevatedClosure`, which implements the `Serializable` interface. There are 2 specializations of this interface for simpler cases, `Run` and `Call`.

## Quick Start

```java
public class Test {
	public static void main(String[] args) throws Exception {
		var other = System.getProperty("user.name");
		
		System.out.println(Elevator.elevator().call(() -> {
			return "Hello World to " + other + " from " + System.getProperty("user.name");
		}));
	}
}
```

If run on Linux as the user `joeb`, would result in ..

```
Hello World to joeb from root
```

## Features

 * Creates an optionally re-usable elevated helper to run blocks of code as an administrator or other elevated user.
 * Works with GUI or Console applications.
 * Works with Graal Native Image (in fact works best).
 * Works with Java 11 or above.
 * No JNI, JNA or other FFI, just uses already available operating system commands.
 * Re-authorization timeout for re-usable helpers. After a certain amount of time, elevated actions must be re-authorized.
 * Elevated tasks block while they are running, and accept an input object and return an output object. While running, the elevated code may send back events to the non-elevated code (the reverse is not currently possible).

## Support

*LiftLib* currently support 3 operating systems. 

 * Linux. Requires that `pkexec` is available.
 * Windows. Requires that `powershell.exe` is available.
 * Mac OS. Requires that `osascript` is available.

## Limitations

 * All objects passed to and returned from an elevated helper must be fully `Serializable`. 
 * When running in interpreted mode (i.e. not Graal native image), the operating system's elevation prompt will identify the process as *Java*.
 * Only one elevated block of code may be run at a time in any JVM (this may be fixed in a future release).
 * Non-elevated code may not communicate with the elevated code after it has been constructed (again, may be fixed).
 * In interpreted mode, if your original `CLASSPATH` was massive, then so will the elevated helpers' `CLASSPATH`. In practice this shouldn't matter to much, the amount of memory used in the helper will depend on what code is run there. 


## How Does It Work

*LiftLib* works by reconstructing the command line that was used to launch the application, subtly altering it so that a different `main(String[] args)` method is run, and then executing this using the operating systems native commands 
for running elevated processes. It then uses either TCP or a Unix Domain Socket to send serializable messages between the two application instances.

There is a little more to it, to take into account various oddities on the different OSs, but that's the basics.
  
 1. Application requests an elevated closure.
 1. LiftLib sets up a the communications channel server, which will either be a random TCP socket or a Unix Domain Socket, depending on the JDK version.
 1. LiftLib checks if there is an already running helper. 
 1. If there isn't a helper, or the one that exists has expired, a new helper will be launched. The new helper will be told how to access the communication channel.
 1. The helper makes a connection back to the communications server. The server will allow no further connections after this.
 1. LibLib serializes the closure and sends it over the wire.
 1. The helper de-serializes the closure, runs the code and then serializes a response.
 1. LifeLib de-serializes the response and returns control to the caller.  

## Usage

The general pattern is ..

 * Obtain an `Elevator` instance, either the default elevator from `Elevator.elevator()`, or configure create a new one with `Elevator.ElevatorBuilder`.
 * Call one of `run()`, `call()` or `closure()` to run your elevated code, passing in an instance of the appropriate interface.
 * `close()` the `Elevator` when you have finished with it (probably do not want to do this if using the default elevator).

The object instance you pass to one of those methods must be fully `Serializable`, so unless it is run in a `static` context as above, 
it is best to create a formal class rather than using lamba syntax (which may easily include the
class it is called from). 
 
### Elevator.run(Run run)

Use this method when you do not require any kind of response (other than exceptions).  

```java
public class Test {
	public static void main(String[] args) throws Exception {
		new Test().doCommand();
	}
	
	public void doCommand() throws Exception {
		try (var elev = new Elevator.ElevatorBuilder().build()) {
			elev.run(new Shutdown());
		}
	}

	@SuppressWarnings("serial")
	public final static class Shutdown implements Run {
		
		@Override
		public void run() throws Exception {
			new ProcessBuilder("shutdown").start().waitFor();
		}
	}
}
```
 
### Elevator.call(Call<RET> run)

Use this method when you want some kind of response from the elevated code. The response itself
must be `Serializable`.

```java

/**
 * A stupid example that doesn't really need to be run as elevated
 */
public class Test {
	public static void main(String[] args) throws Exception {
		new Test().doCommand();
	}
	
	public void doCommand() throws Exception {
		try (var elev = new Elevator.ElevatorBuilder().build()) {
			System.out.println(elev.call(new AddTwoNumbers(123,456)));
		}
	}

	public final static class AddTwoNumbers implements Call<Integer> {
		
		private final int a;
		private final int b;
		
		public AddTwoNumbers() {
			// Need a default constructor, called in elevated JVM
		}
		
		public AddTwoNumbers(int a, int b) {
			this.a = a;
			this.b = b;
		}

		@Override
		public Integer call() throws Exception {
			return a + b;
		}
	}
}
```
 
### Elevator.closure(ElevatedClosure<RET, EVT> run)

This method is for more complex needs. It allows you to not only to response with a return value, but also
for the elevated code to invoke callbacks in the non-elevated JVM. A `Serializable` parameter may be passed
to this callback.

This interface has two generic type parameters, `RET` being the type of the return value, and `EVT` being the
type of the event parameter.

```java
/**
 * A stupid example that tries to kill 10000 processes, starting at PID 10000 
 */
public class Test {
	public static void main(String[] args) throws Exception {
		new Test().doCommand();
	}
	
	public void doCommand() throws Exception {
		try (var elev = new Elevator.ElevatorBuilder().build()) {
 			var count = elev.closure(new KillRange(10000, 20000));
 			System.out.println("I tried to kill " + count  + " processes");
		}
	}

	@SuppressWarnings("serial")
	public final static class KillRange implements ElevatedClosure<Integer, Long> {
		
		private long start;
		private long end;

		public KillRange() {
			// Need a default constructor, called in elevated JVM
		}
		
		public KillRange(long start, long end) {
			this.start = start;
			this.end = end;
		}

		@Override
		public void event(Long pid) {
			// This is invoked on the non-elevated JVM when the elevated JVM calls `proxy.event(Long)`.
			System.out.println("The elevated helper just killed process " + pid);
		}

		@Override
		public Integer call(ElevatedClosure<Integer, Long> proxy) throws Exception {
			var killed = 0;
			for(var i = start ; i < end ; i++) {
				new ProcessBuilder("kill", String.valueOf(i)).start().waitFor();
				killed ++;
			}
			return killed;
		}
		
	}
}

```
 
## Using With Graal Native Image

To be compatible with Graal Native Image, you must extend you applications entry point, i.e. your
`main(String[] args)`. If this methods receives are single element array containing a single argument
in the format `--elevate=<uri>`, where URI will either be an integer number or a file path. For example, 

```java
public static void main(String[] args) {
    if(args.length == 1 && args[0].startsWith("--elevate=")) {
        com.sshtools.liftlib.Helper.main(args[0].substring(10));
    }
    else {    
        // Do your normal command line processing / bootstrapping 
    } 
}
```

You will also need to ensure that any `ElevatedClosure` implementations you have are added to Graal Native Images configuration as serializable classes.
This can be done a number of ways. For example, include a resource at the path `META-INF/native-image/your-app/serialization-config.json` with the content ...

```json
[
   {
    "name":"com.acme.MyElevatedThing"
  }
]
```
  