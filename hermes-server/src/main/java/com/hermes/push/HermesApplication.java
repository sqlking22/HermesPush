package com.hermes.push;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication
@EnableScheduling
public class HermesApplication {
  public static void main(String[] args) {
    System.setProperty("user.timezone", "Asia/Shanghai");
    SpringApplication.run(HermesApplication.class, args);
  }
}
