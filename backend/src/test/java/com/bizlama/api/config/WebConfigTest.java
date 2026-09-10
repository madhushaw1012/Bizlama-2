package com.bizlama.api.config;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.List;

class WebConfigTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertyValues.of(
                "bizlama.web.cors.allowed-origin-patterns="
                        + "http://localhost:*,"
                        + "http://127.0.0.1:*,"
                        + "https://bizlama-api-*.asia-south1.run.app"
        ).applyTo(context);
        context.register(
                MvcConfiguration.class,
                WebConfig.class,
                CorsProbeController.class
        );
        context.refresh();

        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "login",
            "dashboard",
            "activity",
            "receipts",
            "orders",
            "inventory",
            "recipes",
            "feedback",
            "settings"
    })
    void forwardsKnownAngularRoutes(String route) throws Exception {
        mockMvc.perform(get("/" + route))
                .andExpect(forwardedUrl("/index.html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/orders",
            "/actuator/health"
    })
    void doesNotForwardBackendRoutes(String route) throws Exception {
        MvcResult result = mockMvc.perform(get(route)).andReturn();

        assertThat(result.getResponse().getForwardedUrl()).isNull();
    }

    @Test
    void allowsConfiguredCloudRunPreflight() throws Exception {
        mockMvc.perform(options("/api/receipts")
                        .header(
                                "Origin",
                                "https://bizlama-api-12345"
                                        + ".asia-south1.run.app"
                        )
                        .header(
                                "Access-Control-Request-Method",
                                "POST"
                        ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        "https://bizlama-api-12345"
                                + ".asia-south1.run.app"
                ))
                .andExpect(header().string(
                        "Access-Control-Allow-Methods",
                        containsString("OPTIONS")
                ));
    }

    @Test
    void rejectsUnconfiguredCrossOriginPreflight() throws Exception {
        mockMvc.perform(options("/api/receipts")
                        .header("Origin", "https://untrusted.example")
                        .header(
                                "Access-Control-Request-Method",
                                "POST"
                        ))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsWildcardOnlyOriginConfiguration() {
        assertThatThrownBy(() -> new CorsProperties(List.of("https://*")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfiguration {
    }

    @RestController
    static class CorsProbeController {

        @PostMapping("/api/receipts")
        String createReceipt() {
            return "ok";
        }
    }
}
