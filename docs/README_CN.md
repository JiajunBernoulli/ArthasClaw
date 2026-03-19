# ArthasClaw：JVM AI 助手

<p align="center">
  <img src="rectangle.jpg" alt="ArthasClaw Logo" width="400">
</p>

JVM 诊断助手，基于 Arthas 的自然语言诊断工具。

## 简介

ArthasClaw 是一个基于 Arthas 的 JVM 诊断工具，通过自然语言交互方式，让开发者无需记忆复杂的 Arthas 命令，即可快速诊断 Java 应用问题。

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

