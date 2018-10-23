package in4392.cloudcomputing.maininstance;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MainInstanceApplication {
	public static void main(String[] args) throws IOException, NoSuchAlgorithmException, URISyntaxException {
		SpringApplication.run(MainInstanceApplication.class, args);
		MainInstance.startMainLoop();
	}
}
