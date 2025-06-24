package com.julemoran.smooth_web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class SmoothWebApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmoothWebApplication.class, args);
	}

}
