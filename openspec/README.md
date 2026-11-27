# OpenSpec 开发流程

项目使用 OpenSpec 2.2.0 的 `spec-driven` 工作流。Codex 的项目级 skills 位于 [`.agents/skills/`](../.agents/skills/)。

## 安装 CLI

从 [OpenSpec v2.2.0 官方发布页](https://github.com/Fission-AI/OpenSpec/releases/tag/v2.2.0) 下载对应系统的独立二进制，校验 SHA-256，解压后将二进制目录加到当前终端的 `PATH` 前面。运行 `openspec --version` 确认使用的是 2.2.0。下面的命令也可以直接使用二进制路径。

## 开发一项变更

1. 查阅 `openspec list --specs` 和 `openspec list`。用 `$openspec-propose` 准备变更目录中的 `proposal.md`、需求增量、`design.md` 和 `tasks.md`。明确行为、受影响的能力与可验证的场景。
2. 用 `$openspec-apply-change` 实现已明确的范围。按任务记录实现、测试与验收结果。实现中的行为变更同步回对应规范。
3. 运行 `openspec validate --all --strict --no-interactive`。验收完成后用 `$openspec-archive-change` 归档，更新 `openspec/specs/` 中的能力规范。提交前重新运行严格校验。校验通过代表文档结构有效，任务中的验收结果仍需核对。

## 目录约定

- `openspec/specs/` 保存已归档的能力规范，作为后续变更的行为基线。
- `openspec/changes/` 保存进行中的变更，完成的条目放进 `openspec/changes/archive/`。
- 根目录的私有 `specs/` 保留历史资料。按需把可公开、已核实的行为纳入当前相关能力。
