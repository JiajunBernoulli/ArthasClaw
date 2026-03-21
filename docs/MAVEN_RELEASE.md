# ArthasClaw Maven 发布指南

本文档介绍如何将 ArthasClaw 发布到 Maven Central。

## 前置条件

### 1. GPG 密钥

发布到 Maven Central 需要对构建产物进行 GPG 签名。

```bash
# 检查是否有 GPG 密钥
gpg --list-secret-keys

# 如果没有，生成新密钥
gpg --full-generate-key
# 选择: (1) RSA and RSA, 4096 bits, 0 = key does not expire
# 输入姓名、邮箱、密码

# 发布公钥到密钥服务器
gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
gpg --keyserver keys.openpgp.org --send-keys <YOUR_KEY_ID>
gpg --keyserver pgp.mit.edu --send-keys <YOUR_KEY_ID>
```

### 2. Maven Central 账号

1. 访问 https://central.sonatype.com 注册账号
2. 在 [Profile](https://central.sonatype.com/-/profile) 页面生成 **User Token**
3. 配置 `~/.m2/settings.xml`：

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username><!-- User Token 用户名 --></username>
      <password><!-- User Token 密码 --></password>
    </server>
  </servers>
  
  <profiles>
    <profile>
      <id>gpg</id>
      <activation>
        <activeByDefault>true</activeByDefault>
      </activation>
      <properties>
        <gpg.executable>gpg</gpg.executable>
        <gpg.passphrase><!-- GPG 密钥密码 --></gpg.passphrase>
      </properties>
    </profile>
  </profiles>
</settings>
```

### 3. 命名空间验证

确保你拥有 `io.github.jiajunbernoulli` 命名空间的发布权限：
1. 在 Maven Central 中验证 GitHub 账号
2. 添加命名空间验证

---

## 一键发布（推荐）

使用项目根目录下的 `release.sh` 脚本：

```bash
# 发布当前版本
./release.sh

# 发布新版本
./release.sh 0.0.3
```

脚本会自动完成：
- 检查前置条件（GPG、Maven、Git）
- 更新版本号
- 运行测试
- 部署到 Maven Central
- 创建 Git Tag
- 推送到远程仓库

---

## 手动发布步骤

如果需要手动发布，按以下步骤操作：

### 步骤 1: 更新版本号

编辑 `agent/pom.xml` 中的 `<version>` 标签：

```xml
<version>0.0.3</version>
```

> ⚠️ 注意：只修改项目版本，不要修改依赖版本！

### 步骤 2: 运行测试

```bash
cd agent
mvn test
```

### 步骤 3: 部署到 Maven Central

```bash
mvn clean deploy -Prelease
```

这会：
1. 编译项目
2. 生成 Sources JAR
3. 生成 Javadoc JAR
4. GPG 签名所有产物
5. 上传到 Maven Central Portal

### 步骤 4: 完成发布

1. 访问 https://central.sonatype.com/publishing/deployments
2. 找到刚上传的部署
3. 点击 **Publish** 完成发布（或等待自动发布）

### 步骤 5: 创建 Git Tag

```bash
git tag -a v0.0.3 -m "Release v0.0.3"
git push origin v0.0.3
```

---

## 发布后验证

等待几分钟让 Maven Central 同步，然后验证：

```bash
# 检查 Maven Central
curl -I https://repo1.maven.org/maven2/io/github/jiajunbernoulli/arthas-claw/0.0.3/

# 在项目中使用
mvn dependency:get -Dartifact=io.github.jiajunbernoulli:arthas-claw:0.0.3
```

---

## 常见问题

### GPG 签名失败

```
gpg: signing failed: Inappropriate ioctl for device
```

**解决方案**：在 `~/.m2/settings.xml` 中添加 GPG 密码：

```xml
<properties>
  <gpg.passphrase>your-passphrase</gpg.passphrase>
</properties>
```

或者在终端中设置：

```bash
export GPG_TTY=$(tty)
```

### 认证失败

```
Failed to deploy artifacts: Could not transfer artifact ... Return code is: 401
```

**解决方案**：检查 `~/.m2/settings.xml` 中的 User Token 配置是否正确。

### 命名空间未授权

```
Failed to deploy: namespace not owned
```

**解决方案**：在 Maven Central Portal 中验证并添加命名空间所有权。

---

## 相关链接

- [Maven Central Portal](https://central.sonatype.com)
- [OSSRH Guide](https://central.sonatype.org/publish/publish-guide/)
- [GPG Key Servers](https://keyserver.ubuntu.com)
