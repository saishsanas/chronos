package com.chronos.api.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@Hidden
public class RootRedirectController {

    private static final URI SWAGGER_URI = URI.create("/swagger-ui/index.html");

    @GetMapping("/")
    public ResponseEntity<Void> redirectToSwagger() {
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(SWAGGER_URI)
            .build();
    }
}
