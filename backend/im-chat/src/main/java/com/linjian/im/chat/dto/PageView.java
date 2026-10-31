package com.linjian.im.chat.dto;

import java.util.List;

/** 分页结果的统一外壳。items 已经在服务层按 seq 升序排列好，客户端直接渲染。 */
public record PageView<T>(List<T> items, long total) {
}
