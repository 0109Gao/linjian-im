package com.linjian.im.web.controller;

import com.linjian.im.chat.dto.AuthResponse;
import com.linjian.im.chat.dto.LoginRequest;
import com.linjian.im.chat.dto.RegisterRequest;
import com.linjian.im.chat.service.UserService;
import com.linjian.im.common.api.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。REST 在这里只承担「换 token」这一件事，
 * 换取到 token 之后的所有实时交互都走 STOMP。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(userService.login(request));
    }

    @PostMapping("/register")
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(userService.register(request));
    }
}
