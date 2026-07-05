package com.ulisses;

import com.ulisses.face.FaceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FaceProperties.class)
public class UlissesApplication {

	public static void main(String[] args) {
		SpringApplication.run(UlissesApplication.class, args);
	}
}
