---
trigger: manual
description: macOS 跨平台路径处理——处理 symlink、文件路径白名单、跨平台兼容性时手动引入
---

# macos-path.md - macOS 路径处理规范

> 引用方式：`@macos-path`
> 触发场景：macOS 上涉及 symlink、文件路径白名单检查、跨平台路径处理
> 来源：整合自 Claude Code `rules/engineering-practices.md` §10（atdo P1-1 修复案例）

---

## 1. 核心问题

**macOS 上 `path.resolve` 不展开符号链接，`fs.realpathSync` 展开**——两者混用导致路径白名单检查假阳/假阴。

经典陷阱：

```javascript
// ❌ macOS 上 /var → /private/var 是符号链接
const STATE_DIR = '/var/myapp/state';
const userPath = '/var/myapp/state/../config/secret.json';

const stateAbs = path.resolve(STATE_DIR);              // /var/myapp/state
const targetAbs = path.resolve(userPath);              // /var/myapp/config/secret.json
if (targetAbs.startsWith(stateAbs)) { /* PASS */ }     // ❌ 假阳！
```

实际执行时 `fs.realpathSync` 把 `/var/myapp` → `/private/var/myapp`，但 lexical 比较时仍是 `/var/...`——导致**白名单检查失效**。

---

## 2. 正确做法

### 方案 A：两侧 realpath 归一化（推荐，文件存在时）

```javascript
const path = require('path');
const fs = require('fs');

function isPathSafe(filepath, allowedDir) {
  const allowedReal = fs.realpathSync(allowedDir);
  const targetReal = fs.existsSync(filepath)
    ? fs.realpathSync(filepath)
    : path.resolve(filepath);  // 文件不存在时回退到 lexical

  return targetReal.startsWith(allowedReal + path.sep);
}

if (!isPathSafe(userPath, STATE_DIR)) {
  die('reject: path outside allowed dir');
}
```

### 方案 B：lexical 比较 + realpath 二次校验

```javascript
function isPathSafe(filepath, allowedDir) {
  // 第一层：lexical 检查（快速失败）
  const allowedAbs = path.resolve(allowedDir);
  const targetAbs = path.resolve(filepath);
  if (!targetAbs.startsWith(allowedAbs + path.sep)) {
    return false;
  }

  // 第二层：realpath 校验（处理 symlink）
  if (fs.existsSync(filepath)) {
    const targetReal = fs.realpathSync(targetAbs);
    const allowedReal = fs.realpathSync(allowedAbs);
    return targetReal.startsWith(allowedReal + path.sep);
  }

  return true;  // 文件不存在，lexical 通过即可
}
```

---

## 3. 其他 macOS 路径陷阱

### 3.1 `/tmp` 与 `/private/tmp`

```javascript
// macOS 上 /tmp → /private/tmp 是 symlink
// 写日志、临时文件时要意识到
const tmpFile = '/tmp/myapp.log';
const realTmpFile = fs.realpathSync.native(tmpFile);  // /private/tmp/myapp.log
```

### 3.2 `os.homedir()` 的差异

```javascript
const os = require('os');
const home = os.homedir();           // /Users/xxx（macOS）vs /home/xxx（Linux）
const realHome = fs.realpathSync(home); // 跨平台统一
```

### 3.3 Docker 容器内路径

```bash
# 容器内看到的路径 vs 宿主机路径可能不同
# -v /host/path:/container/path 时容器内是 lexical 路径
# 但容器的 /var 也可能是 symlink
```

### 3.4 `path.join` vs `path.resolve`

```javascript
path.join('/var/myapp', '../etc/passwd');        // /var/etc/passwd（不规范化）
path.resolve('/var/myapp', '../etc/passwd');    // /var/etc/passwd（规范化）

// 都需要二次 realpath 检查！
```

---

## 4. 自检清单

路径白名单相关代码必须满足：

- [ ] 用 `fs.realpathSync` 双侧归一化
- [ ] 文件不存在场景有降级路径（lexical 检查）
- [ ] 在 macOS 和 Linux 都跑过测试（Docker 验证跨平台）
- [ ] 测试覆盖：路径穿越、symlink 逃逸、不存在的中间目录

### 4.1 必写测试用例

```javascript
describe('path whitelist', () => {
  test('rejects path traversal via ../', () => {
    expect(isPathSafe('/allowed/../etc/passwd', '/allowed')).toBe(false);
  });

  test('rejects symlink escape', () => {
    // 创建 symlink：/allowed/link → /etc
    fs.symlinkSync('/etc', '/allowed/link');
    expect(isPathSafe('/allowed/link/passwd', '/allowed')).toBe(false);
  });

  test('rejects absolute path outside', () => {
    expect(isPathSafe('/etc/passwd', '/allowed')).toBe(false);
  });

  test('allows nested path', () => {
    expect(isPathSafe('/allowed/sub/file.txt', '/allowed')).toBe(true);
  });

  test('handles nonexistent intermediate dir', () => {
    expect(isPathSafe('/allowed/new-sub/file.txt', '/allowed')).toBe(true);
  });

  test('macOS /var → /private/var symlink', () => {
    if (process.platform === 'darwin') {
      // macOS only
      expect(isPathSafe('/var/myapp/data', '/var/myapp')).toBe(true);
    }
  });
});
```

---

## 5. 反模式 vs 正例

| 反例 | 正例 |
|------|------|
| `path.resolve(a).startsWith(path.resolve(b))` | 两侧都 `realpathSync` 后比较 |
| 不处理 symlink | `fs.realpathSync` 双侧归一化 |
| 文件不存在时崩溃 | lexical 回退 |
| 只在 Linux 测试 | macOS + Linux 都验证 |
| 用 `path.join` 后比较 | `path.resolve` 后再 `realpathSync` |

---

## 6. 跨平台封装示例

```javascript
const path = require('path');
const fs = require('fs');

/**
 * 安全路径检查：跨平台 symlink 感知
 */
function isPathSafe(filepath, allowedDir) {
  const allowedAbs = path.resolve(allowedDir);

  // 快速失败：lexical 检查
  const targetAbs = path.resolve(filepath);
  if (!targetAbs.startsWith(allowedAbs + path.sep) && targetAbs !== allowedAbs) {
    return false;
  }

  // 二次校验：realpath（处理 symlink）
  try {
    const targetReal = fs.realpathSync(targetAbs);
    const allowedReal = fs.realpathSync(allowedAbs);
    return targetReal === allowedReal ||
           targetReal.startsWith(allowedReal + path.sep);
  } catch (err) {
    // 路径不存在时依赖 lexical 检查结果
    return targetAbs.startsWith(allowedAbs + path.sep);
  }
}

module.exports = { isPathSafe };
```

---

## 7. 适用范围

| 场景 | 是否需要 realpath 检查 |
|------|---------------------|
| 文件读取白名单（防止越权） | ✅ 必须 |
| 文件写入白名单（防止覆盖系统文件） | ✅ 必须 |
| 临时目录操作（/tmp 内） | ✅ 必须（macOS） |
| 日志路径拼接 | 一般不需 |
| URL 路径处理 | 一般不需 |
| 跨平台 IPC 路径传递 | ✅ 推荐 |

---

## 8. 与其他规则的关系

| 规则文件 | 关系 |
|---------|------|
| `code-engineering.md` §11 | 跨平台路径处理（精简版） |
| `core-red-lines.md` §3.2 | 路径穿越属于注入防护红线 |
| `verification-checklist.md` | 跨平台测试是验证清单的一部分 |

---

_macos 路径处理规范 v1.0 - 处理文件路径/symlink 时手动引入_