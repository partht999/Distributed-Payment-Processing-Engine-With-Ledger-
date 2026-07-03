package com.distributed.payment_engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableCaching
public class PaymentEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(PaymentEngineApplication.class, args);
	}

}
