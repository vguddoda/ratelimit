package com.example.demo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    /**
     * Handles GET requests to "/api/data".
     * This endpoint will be rate-limited by the Bucket4j filter.
     * @return A simple string response indicating successful access.
     */
    @GetMapping("/data")
    public String getData() {
        return "Here is the protected data!";
    }
}