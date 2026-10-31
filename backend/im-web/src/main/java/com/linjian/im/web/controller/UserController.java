package com.linjian.im.web.controller;

import com.linjian.im.chat.dto.UserBrief;
import com.linjian.im.chat.service.UserService;
import com.linjian.im.common.api.Result;
import com.linjian.im.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 联系人搜索。W7 没有它就无法发起第一个会话——客户端不可能凭空知道对方的 userId。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/search")
    public Result<List<UserBrief>> search(@RequestParam("q") String keyword) {
        return Result.success(userService.search(keyword, CurrentUser.id(), 20));
    }
}
