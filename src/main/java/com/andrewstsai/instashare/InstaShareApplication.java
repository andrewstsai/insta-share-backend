package com.andrewstsai.instashare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InstaShareApplication {

	public static void main(String[] args) {
		SpringApplication.run(InstaShareApplication.class, args);
	}

}
