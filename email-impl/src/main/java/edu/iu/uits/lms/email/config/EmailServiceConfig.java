package edu.iu.uits.lms.email.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "lmsemail")
@Getter
@Setter
public class EmailServiceConfig {
   private boolean enabled;
   private boolean signingEnabled;
   private String defaultFrom;
   private String defaultUnsignedTo;
   private String env;
}
