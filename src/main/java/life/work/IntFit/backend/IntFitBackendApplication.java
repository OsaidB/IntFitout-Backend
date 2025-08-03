package life.work.IntFit.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnableTransactionManagement
@SpringBootApplication
public class IntFitBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(IntFitBackendApplication.class, args);
	}

}
