package com.sshtools.liftlib;

import com.sshtools.liftlib.Elevator.ReauthorizationPolicy;

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
