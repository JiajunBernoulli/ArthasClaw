# 🦞 ArthasClaw - JVM AI Assistant

<p align="center">
  <img src="rectangle.jpg" alt="ArthasClaw Logo" width="800">
</p>

JVM 诊断助手，基于 Arthas 的自然语言诊断工具。

## 简介

ArthasClaw 是一个基于 Arthas 的 JVM 诊断工具，通过自然语言交互方式，让开发者无需记忆复杂的 Arthas 命令，即可快速诊断 Java 应用问题。

## 优点
在JVM诊断场景下，ArthasClaw相比通用Agent有以下优点：
- **依赖少**：OpenClaw需要Node22+版本，Nanobot需要Python3.11+版本，而ArthasClaw只需Java8+版本，能一键安装在任何Java应用的服务器中。
- **速度快**： 本地的Claude Code、Trae等工具与远程服务器的Arthas通信网络可能较慢，甚至不可达。而ArthasClaw可以直接运行在Java应用所在的服务器中，从本地直接请求Arthas MCP，速度更快、稳定性更高。

## 特性

- 🤖 **自然语言交互**：用中文/英文提问，自动解析意图
- 📊 **智能分析报告**：AI 解读诊断结果
- 💻 **多模式支持**：自然语言、Arthas 命令、Shell 命令

## 快速开始

### 一键安装启动

```bash
curl -sL https://raw.githubusercontent.com/JiajunBernoulli/ArthasClaw/main/start.sh | sh
```

脚本会自动：
1. 从 Maven Central 下载 JAR
2. 检查 OpenAI 环境变量，缺失时提示输入
3. 列出可选的 Java 进程供选择
4. 启动 ArthasClaw 并连接目标进程
5. 在 TUI 界面中输入自然语言问题

<p align="center">
  <img src="tui_cn.gif" alt="ArthasClaw TUI Interface" width="600">
</p>