# mpvRx “5 秒等待”问题调查档案

打开视频时偶发等待整整 5 秒才开始播放的问题：从发现、根因定位、两次被证伪的
解释，到第一版修复及其真机复测失败的完整记录。

- **全流程摘要**：[5秒等待问题-全流程摘要.md](5秒等待问题-全流程摘要.md)
- **证据摘录**（带导读的日志节选）：`evidence/`
- **完整原始日志**：`logs/`（mpvrx-3 / 5 / 7 / 10，与摘要文末的日志索引一一对应）

设备：Huawei Mate 70 Pro（Android 12）；构建：fzs209/mpvRx 分支
`fix/fongmi-legacy-gpu-osd-crash` 的 FongMi patched 构建（vo=mediacodec_embed）。
