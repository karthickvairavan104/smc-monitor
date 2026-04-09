package com.smcmonitor.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/info")
public class InfoController {

    @Value("${app.deployment.slot:blue}") private String slot;
    @Value("${app.deployment.version:1.0.0}") private String version;

    @GetMapping
    public Map<String, String> info() {
        return Map.of("slot", slot, "version", version, "status", "UP");
    }
}
