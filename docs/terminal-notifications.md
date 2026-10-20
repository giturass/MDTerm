# 终端系统通知

MDTerm 支持 iTerm2 风格的 OSC 9 通知。终端程序输出 `ESC ] 9 ; 消息 BEL` 或
`ESC ] 9 ; 消息 ESC \` 时，MDTerm 会将消息发送到 Android 的“会话提醒”通知渠道。
本地 Shell、SSH 和 PRoot 内的程序都可以使用，只需让控制序列传到 MDTerm 的终端。

```sh
# 执行后在 5 秒内切到其他应用、其他会话或锁屏。
sleep 5; printf '\033]9;需要你处理\007'

# 也支持 ST（ESC + 反斜杠）结尾。
sleep 5; printf '\033]9;任务已完成\033\\'
```

应用正在前台显示并聚焦于发出消息的会话时，不发送系统通知。应用在后台、没有窗口焦点、
显示其他会话或展开会话侧栏时会提醒；关闭终端界面后，只要后台服务和会话仍在运行，也能接收。

每个会话最多保留一条提醒，后续消息更新其内容。5 秒内相同消息被忽略，其他内容的更新使用
`onlyAlertOnce` 避免反复响铃。通知标题使用自定义会话名称，没有名称时显示会话编号。
消息最多显示 2000 个 UTF-16 单元，截断不会拆开代理对。

点击通知直接打开对应会话，返回查看该会话或会话结束、关闭时清除提醒。会话顺序变化不影响
通知定位；过期通知不会新建 Shell。OSC 9 本身不包含“已处理”事件，因此不会根据普通终端
输出或标题变化推断任务已恢复。

在 Android 系统设置的“应用 → MDTerm → 通知 → 会话提醒”中，可以关闭提醒或调整声音、
振动、锁屏显示和弹出方式。Android 13 及以上需要允许应用通知；当前项目保留 target SDK 28，
首次通知权限对话框由系统控制。关闭通知权限或渠道不会影响终端运行。

`OSC 9;4` 和 `OSC 9;4;…` 是进度条控制序列，不会生成消息通知。空消息也会忽略。
普通 BEL 的振动／响铃设置保持原来的行为。

## Codex CLI

可将以下设置合并到现有 `~/.codex/config.toml` 的 `[tui]` 段，重启 Codex 后生效：

```toml
[tui]
notifications = ["approval-requested"]
notification_method = "osc9"
notification_condition = "always"
```

显式选择 `osc9`，让 Codex 发出带消息内容的通知。`always` 让 Codex 不依赖终端焦点报告，
由 MDTerm 判断当前会话是否正在查看。如果还需要每轮完成提醒，可将 `notifications` 改为
`["approval-requested", "agent-turn-complete"]`。终端复用器需要配置为透传 OSC 9。

## 验证

现有 GitHub Actions 的 `testDebugUnitTest` 会包含以下回归测试：

- `NotificationTest`：BEL／ST、分段输入、中文和 emoji、取消的序列、空消息和进度条。
- `TerminalSessionNotificationsTest`：通知内容、独立会话、不可变点击 Intent、去重、渠道关闭、
  消息长度和残留通知清理。
- `BookmarkStartupTest` 中的通知用例：后台接收、首次打开和已打开界面的跳转、焦点切换、
  过期通知和会话关闭。

设备上还应验证：

1. 用上面的延时命令分别在前台、后台、另一会话和锁屏时发送，核对提醒条件。
2. 在两个会话发送不同消息，分别点击通知，确认跳转准确且其他会话的通知仍保留。
3. 关闭界面后发送，再点击通知，确认后台服务仍能接收并恢复对应会话。
4. 拒绝通知权限或关闭“会话提醒”渠道，再发送消息，确认不提醒且终端正常。
5. 连续发送相同消息、进度序列和普通 BEL，确认去重、进度忽略及原有响铃行为。

参考：[iTerm2 控制序列](https://iterm2.com/documentation-escape-codes.html)、
[Android 通知渠道](https://developer.android.com/develop/ui/views/notifications/channels)、
[Android 通知权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)、
[OpenAI 官方通知配置](https://learn.chatgpt.com/docs/config-file/config-advanced#notifications)。
