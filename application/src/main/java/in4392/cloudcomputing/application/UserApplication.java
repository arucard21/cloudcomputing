package in4392.cloudcomputing.application;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UserApplication {
	public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
		SpringApplication.run(UserApplication.class, args);
	}
}
