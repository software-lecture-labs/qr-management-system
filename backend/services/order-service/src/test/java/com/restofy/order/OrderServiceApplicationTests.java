package com.restofy.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class OrderServiceApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoadsThroughMain() {
		assertTrue(context.containsBean("orderCheckoutService"), "the application started through main() and wired its services");
	}

}
