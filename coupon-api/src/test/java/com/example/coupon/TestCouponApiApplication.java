package com.example.coupon;

import org.springframework.boot.SpringApplication;

public class TestCouponApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(CouponApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
