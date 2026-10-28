package com.ken.platform.identity;

import com.ken.platform.foundation.config.PlatformService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
@PlatformService("identity-service")
public class IdentityApplication {

  public static void main(String[] args) {
    if (args.length > 0
        && (args[0].equals("bootstrap")
            || args[0].equals("bootstrap-local")
            || args[0].equals("bootstrap-monitoring")
            || args[0].startsWith("bootstrap-services-"))) {
      System.exit(
          com.ken.platform.identity.maintenance.IdentityBootstrapMain.run(System.getenv(), args));
      return;
    }
    SpringApplication.run(IdentityApplication.class, args);
  }
}
