# LiftLib

A small, no dependency Java library whose only job is to create an *Elevated JVM* for running small blocks of Java code as a system administrator. The user will be prompted to accept the elevation.

The code must be wrapped in an `ElevatedClosure`, which extends `Callback`, but also implements the `Serializable`. 

## Quick Start

```java
public class Test {
	public static void main(String[] args) throws Exception {
		var d = 300;
		var other = System.getProperty("user.name");
		var builder = new Elevator.ElevatorBuilder();
		builder.withoutFailOnCancel();
		builder.withReauthorizationPolicy(ReauthorizationPolicy.NEVER);
		try (var elev = builder.build()) {
			System.out.println(elev.call(() -> {
				var a = 100;
				var b = 200;
				var c = a + b + d;
				return c;
			}));
			System.out.println(elev.call(() -> {
				return "Hello World to " + other + " from " + System.getProperty("user.name");
			}));
		}
	}
}
```

The entire instance must be fully serializable, so unless it is run in a `static` context as above, it is best to create a formal class rather than using lamba syntax (which may easily include the class it is called from).

```java

public class Test {
	public static void main(String[] args) throws Exception {
		new Test().doCommand();
	}
	
	public void doCommand() throws Exception {
		try (var elev = new Elevator.ElevatorBuilder().build()) {
			System.out.println(elev.call(new AddTwoNumbers(123,456)));
		}
	}

	public final static class AddTwoNumbers implements ElevatedClosure<Integer> {
		
		private final int a;
		private final int b;
		
		public RunCmd(int a, int b) {
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

## Support

*LiftLib* currently support 3 operating systems. 

 * Linux. Requires that `pkexec` is available.
 * Windows. Requires that `powershell.exe` is available.
 * Mac OS. Requires that `osascript` is available.
 
## Limitations

All 3 operating systems will currently identify the requesting application as either Java on Linux or Window, or as osascript on Mac OS.