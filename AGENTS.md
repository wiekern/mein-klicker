# MeinKlicker 开发约定

应用结构、构建和设备验证见 [CLAUDE.md](CLAUDE.md)。涉及用户可见行为、数据格式或跨模块契约的变更，按 [OpenSpec 工作流](openspec/README.md) 开发。

先用 `openspec --version` 确认 CLI 版本，然后查看 `openspec/specs/` 和正在进行的变更。用项目级 `openspec-propose` 技能起草或补全提案、需求场景、设计和任务，再进入 `openspec-apply-change`。实现时按验证结果更新任务。验收完成后，严格校验并以 `openspec-archive-change` 归档。用能力名称组织规范，避免同一行为散落在多个能力里。
