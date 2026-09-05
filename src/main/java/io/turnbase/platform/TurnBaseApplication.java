package io.turnbase.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TurnBaseApplication {

  public static void main(String[] args) {
    SpringApplication.run(TurnBaseApplication.class, args);
  }
}
