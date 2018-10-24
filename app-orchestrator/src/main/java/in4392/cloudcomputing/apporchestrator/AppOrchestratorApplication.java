package in4392.cloudcomputing.apporchestrator;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AppOrchestratorApplication {
	public static void main(String[] args) throws NoSuchAlgorithmException, IOException, URISyntaxException {
		SpringApplication.run(AppOrchestratorApplication.class, args);
		AppOrchestrator.startMainLoop();
	}
}
