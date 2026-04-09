package com.smcmonitor.controller;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.smcmonitor.model.User;
import com.smcmonitor.repository.UserRepository;
import com.smcmonitor.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepo;
    private final JwtUtil        jwt;

    @Value("${google.client-id}") private String googleClientId;

    /** POST /api/auth/google  — exchange Google ID token for our JWT */
    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@RequestBody Map<String, String> body) {
        String credential = body.get("credential");
        if (credential == null || credential.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Missing credential"));

        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(googleClientId))
                .build();

            GoogleIdToken idToken = verifier.verify(credential);
            if (idToken == null)
                return ResponseEntity.status(401).body(Map.of("error", "Invalid Google token"));

            GoogleIdToken.Payload p = idToken.getPayload();
            String googleId = p.getSubject();
            String email    = p.getEmail();
            String name     = (String) p.get("name");
            String picture  = (String) p.get("picture");

            User user = userRepo.findByGoogleId(googleId)
                .orElseGet(() -> userRepo.findByEmail(email)
                    .orElseGet(() -> {
                        log.info("New user: {}", email);
                        return userRepo.save(User.builder()
                            .googleId(googleId).email(email).name(name).picture(picture)
                            .equityCurve(List.of(10_000.0))
                            .build());
                    }));

            // Sync profile fields if changed
            if (!googleId.equals(user.getGoogleId()) || !name.equals(user.getName())) {
                user.setGoogleId(googleId); user.setName(name); user.setPicture(picture);
                userRepo.save(user);
            }

            return ResponseEntity.ok(Map.of(
                "token", jwt.generateToken(user.getId(), user.getEmail()),
                "user",  Map.of("id", user.getId(), "email", user.getEmail(),
                                "name", user.getName(), "picture", user.getPicture())
            ));
        } catch (Exception e) {
            log.error("Google auth error: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Authentication failed"));
        }
    }

    /** GET /api/auth/me */
    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        return userRepo.findById((String) auth.getPrincipal())
            .map(u -> ResponseEntity.ok(Map.of(
                "id", u.getId(), "email", u.getEmail(),
                "name", u.getName(), "picture", u.getPicture(),
                "balance", u.getBalance(), "settings", u.getSettings())))
            .orElse(ResponseEntity.notFound().build());
    }
}
