package com.restofy.waiter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
		properties = "restofy.notification.dispatcher.enabled=false")
class WaiterServiceApplicationTests {

	@Test
	void contextLoadsThroughMain() {
	}

}
