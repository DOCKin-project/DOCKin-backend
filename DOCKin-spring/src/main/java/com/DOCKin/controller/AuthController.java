package com.DOCKin.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    //login(로그인 API)
    @PostMapping("/login")
    public ResponseEntity<Map<String,String>> authorize(){
        return ResponseEntity.ok(null);
    }
}
