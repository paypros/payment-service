package com.ciberaccion.paypro.controller;

import com.ciberaccion.paypro.dto.LoginRequest;
import com.ciberaccion.paypro.dto.LoginResponse;
import com.ciberaccion.paypro.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}