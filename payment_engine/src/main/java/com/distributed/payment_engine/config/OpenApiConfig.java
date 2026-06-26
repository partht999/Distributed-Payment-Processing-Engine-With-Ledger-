package com.distributed.payment_engine.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentEngineOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Distributed Payment Processing Engine API")
                        .description("REST API documentation for the Payment Engine. Handles Wallets, P2P Transfers, and Merchant Payments.")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("Engineering Team")
                                .email("engineering@paymentengine.local"))
                        .license(new License().name("Apache 2.0").url("http://springdoc.org")));
    }
}
