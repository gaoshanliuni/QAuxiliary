# FakeFriendAddDateNt 局域网 Debug Server

这个目录提供 `FakeFriendAddDateNt` 的本地调试服务器，用于在同一局域网内接收模块日志、下发白名单调试命令。

## 1. 启动方式

```bash
python tools/debug/fake_friend_add_date_debug_server.py --host 0.0.0.0 --port 8848
```

默认地址示例：`http://192.168.50.60:8848`

## 2. 使用前准备

1. 手机和电脑在同一局域网。
2. Windows 防火墙放行 `8848` 入站。
3. 模块内开启：
   - `debug_log`
   - `debug_server_enabled`
4. 模块内配置：
   - `debug_server_url = http://192.168.50.60:8848`
5. 如需远程调参，再开启：
   - `debug_command_polling_enabled`

## 3. HTTP 接口

- `POST /qauxv/log`
  - 接收模块上报日志。
- `GET /qauxv/commands`
  - 模块轮询命令队列。
- `POST /qauxv/command-result`
  - 模块回传命令执行结果。

日志会打印到终端，并写入：

`tools/debug/logs/fake_friend_add_date_debug.log`

## 4. 命令输入（服务器终端）

支持白名单命令：

- `ping`
- `dump_config`
- `dump_hook_state`
- `set_debug_log true|false`
- `set_debug_server_enabled true|false`
- `set_target 3394944898`
- `set_fake_add_date 2023-01-02`
- `clear_debug_counters`

示例：

```text
dump_config
set_target 3394944898
set_fake_add_date 2023-01-02
dump_hook_state
```

## 5. 安全说明

1. 仅用于可信局域网调试，不要暴露到公网。
2. 服务器不支持 shell 执行、不支持 eval、不支持任意代码执行。
3. 仅接收白名单命令，不支持任意反射调用。
4. 模块侧不上传 token/cookie/完整敏感 URL 参数。
