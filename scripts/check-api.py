#!/usr/bin/env python3
"""接口验收脚本：正常路径 + 异常路径全覆盖。

为什么单独写这个而不是只依赖集成测试：
集成测试验证的是「代码按设计工作」，库是 im_test、数据是自己造的。这里的脚本打的是
**正在运行的服务**（8083 + 真实 MySQL + 真实 Redis），检查「部署起来的这套东西，面对
不守规矩的请求时行为是否仍然可控」。

约定说明（这是本项目的既定设计，不是脚本的假设）：
  - 安全层（Spring Security 过滤器链）返回**真实 HTTP 状态码**：401 / 403。
  - 业务层（Controller / Service）返回 **HTTP 200 + 业务码**，由 GlobalExceptionHandler
    统一出口。前端 request.js 两个都判断。
  因此断言分两种：
      expect_status —— 校验 HTTP 状态（安全层）
      expect_code   —— 校验 body.code（业务层）

覆盖四类：
  A. 认证边界   —— 无 token / 坏 token / 空 Bearer / 错误密码
  B. 权限边界   —— 普通用户打管理员接口、非成员读别人的会话
  C. 参数边界   —— 漏字段、超长、越界分页、不存在的 ID、类型错误的路径变量
  D. 业务规则   —— 不能禁用自己 / 重复用户名 / 重复入群应幂等

用法：python scripts/check-api.py
返回码：0 全部符合预期；1 有不符合项。
"""
import json
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8083"
GROUP_ID = "109903710661906432"          # 冒烟测试群，linjian 在里面，admin 不在
LINJIAN_ID = "109859320094527488"
DEMO_ID = "109859320094527489"

# 「64 个 emoji 能建群」这条必须真的建出来才能验证，所以给它一个可辨识的前缀，
# 方便事后清理。脚本结尾会把清理 SQL 打出来——不要让它变成库里查不出来的垃圾。
EMOJI_PROBE_PREFIX = "验收探针-"

results = []


def call(method, path, token=None, body=None, raw_auth=None):
    headers = {}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    if raw_auth is not None:
        headers["Authorization"] = raw_auth
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, (json.loads(raw) if raw else {})
        except json.JSONDecodeError:
            return e.code, {"raw": raw[:200]}
    except Exception as e:
        return -1, {"error": str(e)}


def check(group, name, expect_status=None, expect_code=None, note=""):
    """执行并记录。expect_status 与 expect_code 至少给一个。"""
    def run(method, path, **kw):
        status, body = call(method, path, **kw)
        problems = []
        if expect_status is not None and status != expect_status:
            problems.append(f"HTTP {status}≠{expect_status}")
        if expect_code is not None and body.get("code") != expect_code:
            problems.append(f"code {body.get('code')}≠{expect_code}")
        got = f"HTTP {status} / code {body.get('code')}"
        results.append((group, name, problems, got, note))
    return run


def login(username, password="123456"):
    s, b = call("POST", "/api/auth/login", body={"username": username, "password": password})
    return b["data"]["accessToken"] if s == 200 and b.get("code") == 0 else None


def main():
    tok = login("linjian")
    atok = login("admin")
    if not tok or not atok:
        print("登录失败：服务是否在 8083 运行？")
        return 1

    # ---------------- A. 认证边界（安全层，真实状态码） ----------------
    check("A 认证", "无 token 访问会话列表", expect_status=401, expect_code=4010)(
        "GET", "/api/sessions")
    check("A 认证", "签名错误的 token", expect_status=401, expect_code=4010)(
        "GET", "/api/sessions", token="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.bad")
    check("A 认证", "空 Bearer", expect_status=401, expect_code=4010)(
        "GET", "/api/sessions", raw_auth="Bearer ")
    check("A 认证", "错误密码登录", expect_code=4111, note="业务层，HTTP 200" )(
        "POST", "/api/auth/login", body={"username": "linjian", "password": "wrong"})
    check("A 认证", "不存在的用户名登录", expect_code=4111)(
        "POST", "/api/auth/login", body={"username": "nobody_here", "password": "123456"})

    # ---------------- B. 权限边界（安全层，真实状态码） ----------------
    check("B 权限", "普通用户打 /api/admin/online", expect_status=403, expect_code=4030)(
        "GET", "/api/admin/online", token=tok)
    check("B 权限", "普通用户打 /api/admin/users", expect_status=403, expect_code=4030)(
        "GET", "/api/admin/users", token=tok)
    check("B 权限", "普通用户禁用他人", expect_status=403, expect_code=4030)(
        "PUT", f"/api/admin/users/{DEMO_ID}/status", token=tok, body={"status": 0})
    check("B 权限", "普通用户读审计接口", expect_status=403, expect_code=4030)(
        "GET", f"/api/admin/sessions/{GROUP_ID}/messages", token=tok)

    # ---------------- B'. 会话内权限（业务层） ----------------
    check("B' 会话内", "成员读群消息", expect_status=200, expect_code=0)(
        "GET", f"/api/sessions/{GROUP_ID}/messages?limit=2", token=tok)
    check("B' 会话内", "非成员读群消息", expect_code=4101)(
        "GET", f"/api/sessions/{GROUP_ID}/messages?limit=2", token=atok)
    check("B' 会话内", "非成员读成员列表", expect_code=4101)(
        "GET", f"/api/sessions/{GROUP_ID}/members", token=atok)
    check("B' 会话内", "非成员报已读", expect_code=4101)(
        "POST", f"/api/sessions/{GROUP_ID}/read", token=atok, body={"lastReadSeq": 1})

    # ---------------- C. 参数边界（业务层） ----------------
    check("C 参数", "不存在的会话读消息", expect_code=4103)(
        "GET", "/api/sessions/999999/messages?limit=2", token=tok)
    check("C 参数", "路径变量非数字（/sessions/abc/messages）", expect_code=4000,
          note="当前 5000，见报告 K-1")("GET", "/api/sessions/abc/messages", token=tok)
    check("C 参数", "分页 size=10000 不应报错", expect_status=200)(
        "GET", "/api/sessions?page=1&size=10000", token=tok)
    check("C 参数", "分页 page=0", expect_status=200)(
        "GET", "/api/sessions?page=0&size=10", token=tok)
    check("C 参数", "分页 size=0", expect_status=200)(
        "GET", "/api/sessions?page=1&size=0", token=tok)
    check("C 参数", "分页 size=-5", expect_status=200)(
        "GET", "/api/sessions?page=1&size=-5", token=tok)
    check("C 参数", "搜索空关键词", expect_status=200, expect_code=0)(
        "GET", "/api/users/search?q=", token=tok)
    check("C 参数", "搜索缺 q 参数", expect_code=4000, note="当前 5000，见报告 K-2")(
        "GET", "/api/users/search", token=tok)
    check("C 参数", "开群缺 name", expect_code=4000)(
        "POST", "/api/sessions/group", token=tok, body={"memberIds": [DEMO_ID]})
    check("C 参数", "开群空成员", expect_code=4000)(
        "POST", "/api/sessions/group", token=tok, body={"name": "空群", "memberIds": []})
    check("C 参数", "开群名 65 字（上限 64）", expect_code=4000)(
        "POST", "/api/sessions/group", token=tok,
        body={"name": "群" * 65, "memberIds": [DEMO_ID]})
    # 这条是唯一会真的落库的用例：要验证「64 个 emoji 能通过」，就必须让它成功。
    # 群名带固定前缀，便于事后清理（脚本末尾会打印清理命令）。
    check("C 参数", "开群名 64 个 emoji", expect_status=200, expect_code=0,
          note="按码点算 64，应通过；会落库，见末尾清理提示")(
        "POST", "/api/sessions/group", token=tok,
        body={"name": EMOJI_PROBE_PREFIX + "😀" * 59, "memberIds": [DEMO_ID]})
    check("C 参数", "单聊 open 缺 peerUserId", expect_code=4000)(
        "POST", "/api/sessions/open", token=tok, body={})
    check("C 参数", "单聊 open 传不存在的用户", expect_code=4110)(
        "POST", "/api/sessions/open", token=tok, body={"peerUserId": 999999})
    check("C 参数", "禁用接口漏传 status", expect_code=4000,
          note="@NotNull Integer 生效")(
        "PUT", f"/api/admin/users/{DEMO_ID}/status", token=atok, body={})
    check("C 参数", "禁用接口 status=9", expect_code=4000)(
        "PUT", f"/api/admin/users/{DEMO_ID}/status", token=atok, body={"status": 9})

    # ---------------- D. 业务规则 ----------------
    users = call("GET", "/api/admin/users?page=1&size=50", token=atok)[1]
    admin_id = next((u["id"] for u in users["data"]["items"] if u["username"] == "admin"), None)
    check("D 业务", "管理员禁用自己", expect_code=4120)(
        "PUT", f"/api/admin/users/{admin_id}/status", token=atok, body={"status": 0})
    check("D 业务", "注册已存在用户名", expect_code=4112)(
        "POST", "/api/auth/register", body={"username": "linjian", "password": "123456"})
    check("D 业务", "重复加入已在群里的成员（应幂等成功）", expect_status=200, expect_code=0,
          note="added 应为 0")(
        "POST", f"/api/sessions/{GROUP_ID}/members", token=tok,
        body={"userIds": [LINJIAN_ID]})
    check("D 业务", "加人传空数组", expect_code=4000)(
        "POST", f"/api/sessions/{GROUP_ID}/members", token=tok, body={"userIds": []})

    # ---------------- 输出 ----------------
    print()
    cur = None
    bad = 0
    for group, name, problems, got, note in results:
        if group != cur:
            print(f"\n{group}")
            cur = group
        mark = "OK  " if not problems else "FAIL"
        extra = f"   ← {'; '.join(problems)}" if problems else (f"   ({note})" if note else "")
        print(f"  [{mark}] {name:34s} {got}{extra}")
        if problems:
            bad += 1
    print(f"\n合计 {len(results)} 条：符合预期 {len(results) - bad}，不符合 {bad}")

    print(f"""
清理提示：「开群名 64 个 emoji」这条会真的建一个群，名字以「{EMOJI_PROBE_PREFIX}」开头。
反复跑本脚本会累积，按下面的 SQL 清掉（不要直接压演示库就跑，压完记得清）。
**用 docker exec 执行时务必加 --default-character-set=utf8mb4**，否则容器内 mysql 客户端的
character_set_client 默认是 latin1，「{EMOJI_PROBE_PREFIX}」这个中文前缀会被按 latin1 解释，
匹配不上任何行——看起来执行成功，探针群还在：

  SET NAMES utf8mb4;

  DELETE i FROM im_inbox i JOIN im_session s ON s.id=i.session_id
    WHERE s.name LIKE '{EMOJI_PROBE_PREFIX}%';
  DELETE rc FROM im_read_cursor rc JOIN im_session s ON s.id=rc.session_id
    WHERE s.name LIKE '{EMOJI_PROBE_PREFIX}%';
  DELETE m FROM im_message m JOIN im_session s ON s.id=m.session_id
    WHERE s.name LIKE '{EMOJI_PROBE_PREFIX}%';
  DELETE mm FROM im_session_member mm JOIN im_session s ON s.id=mm.session_id
    WHERE s.name LIKE '{EMOJI_PROBE_PREFIX}%';
  DELETE FROM im_session WHERE name LIKE '{EMOJI_PROBE_PREFIX}%';

注意：这里必须写字面量，不要写成 SET @p='...' 再用 CONCAT(@p,'%')。
用户变量的排序规则是连接的默认值（utf8mb4_0900_ai_ci），而 im_session.name 是
utf8mb4_general_ci，两者在 LIKE 里比较会直接报
「Illegal mix of collations」。字面量会继承列的排序规则，所以没问题。
""")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
