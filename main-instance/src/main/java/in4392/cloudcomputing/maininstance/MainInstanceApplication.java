package in4392.cloudcomputing.maininstance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MainInstanceApplication {
	public static void main(String[] args) {
		SpringApplication.run(MainInstanceApplication.class, args);
		MainInstance.run();
		// example usage of deploy method
//		MainInstance.deployDefaultEC2("mainInstance");
	}
}
