package com.pnp.ecommerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the PnP Ecommerce service.
 *
 * <p>Why a plain {@code @SpringBootApplication} main class instead of a
 * custom bootstrap (e.g. programmatic {@code new SpringApplication(...)}
 * with builders): the default auto-configuration is exactly what this
 * service needs, and overriding it would add review surface area without
 * functional benefit.
 */
@SpringBootApplication
public class EcommerceApplication {

    public static void main(final String[] args) {
        SpringApplication.run(EcommerceApplication.class, args);
    }
}
