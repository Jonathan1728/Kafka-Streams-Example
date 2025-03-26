package example.notificationgeneration.common.config;


import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;



@Configuration
public class EnvironmentConfig {

    private final String kafkaSecurityConfigFilePath;
    private final String registryTrustStoreLocation;
    private final String registryTrustStorePassword;
    private final String registryKeyStoreLocation;
    private final String registryKeyStorePassword;

    public EnvironmentConfig(@Value("${kafka.security.config.file.path}") String kafkaSecurityConfigFilePath,
                             @Value("${schema.registry.trust.store.location}") String registryTrustStoreLocation,
                             @Value("${schema.registry.trust.store.password}") String registryTrustStorePassword,
                             @Value("${schema.registry.key.store.location}") String registryKeyStoreLocation,
                             @Value("${schema.registry.key.store.password}") String registryKeyStorePassword) {
        this.kafkaSecurityConfigFilePath = kafkaSecurityConfigFilePath;
        this.registryTrustStoreLocation = registryTrustStoreLocation;
        this.registryTrustStorePassword = registryTrustStorePassword;
        this.registryKeyStoreLocation = registryKeyStoreLocation;
        this.registryKeyStorePassword = registryKeyStorePassword;
    }

    @PostConstruct
    public void setSecurityConfig() {
        System.setProperty("java.security.auth.login.config", kafkaSecurityConfigFilePath);
        System.setProperty("javax.net.ssl.trustStore", registryTrustStoreLocation);
        System.setProperty("javax.net.ssl.trustStorePassword", registryTrustStorePassword);
        System.setProperty("javax.net.ssl.keyStore", registryKeyStoreLocation);
        System.setProperty("javax.net.ssl.keyStorePassword", registryKeyStorePassword);
    }
}