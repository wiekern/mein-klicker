# MeinKlicker

一个简单的 Android 自动点击器：按顺序自动点击、保存点击方案，还支持"亲手点第一下、后面自动完成"的手动触发模式。通过 `adb install` 侧载安装。

## MVP 功能

- 按顺序自动点击多个位置，支持步骤间隔和循环
- 保存 / 管理点击方案（拖动悬浮标记设置位置）
- 手动触发：亲手点击第一个位置后，自动完成后续点击

只需要开启**无障碍服务**这一项权限；悬浮窗由无障碍服务托管，不需要单独的悬浮窗权限。

## 编译

需要 JDK 17 和 Android SDK（compileSdk 34）。Android Studio Iguana 及以上可以直接打开项目根目录，等 Gradle Sync 完成后点 *Build → Build APK(s)*。

命令行：

```bash
# 1. 在项目根目录写 local.properties，指向本机 Android SDK（Windows 路径用正斜杠）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 使用 JDK 17，例如 Android Studio 自带的 JBR
export JAVA_HOME="/path/to/Android Studio/jbr"

# 3. 编译 / 测试
./gradlew assembleDebug          # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

首次编译会从 Google / Maven Central 下载依赖。

## 安装到手机

1. 手机：设置 → 关于手机 → 连续点"版本号"打开开发者选项；
2. 开发者选项里打开 **USB 调试**。ColorOS 上还要打开"**禁止权限监控**"，否则 `adb install` 可能被拦截；
3. 用数据线连电脑，在手机上允许 USB 调试授权，然后：

```bash
adb devices                                              # 能看到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s MeinKlicker                                # 查看日志
```

用 `adb install` 安装不受 Android 13+"受限制的设置"影响；如果是用文件管理器装的 APK，开启无障碍前要先到"应用信息 → ⋮ → 允许受限制的设置"。

注意：每台电脑的 debug 签名不同，换电脑编译后覆盖安装会失败，需要先卸载旧版本（方案数据会一起删除）。

## 首次设置

打开 App，主界面顶部的状态卡逐项处理：

1. **无障碍服务** → 去开启 → 在系统列表中找到"MeinKlicker 自动点击"并打开；
2. **电池优化** → 去设置 → 允许（忽略电池优化）；
3. **自启动 / 后台运行** → 去设置 → 允许 MeinKlicker 自启动、后台运行；
4. 在最近任务里把 MeinKlicker 下拉**锁定**。

**OriginOS 7 首批内测版注意**：在无障碍服务详情页打开或关闭开关后，按返回离开时服务的开 / 关状态可能不对，请用 **Home 手势（从底部上滑）** 离开，并以 MeinKlicker 主界面显示的状态为准。也可以用电脑执行：

```bash
adb shell settings put secure enabled_accessibility_services com.meinklicker/com.meinklicker.service.ClickAccessibilityService
```

注意这条命令会覆盖已开启的其他无障碍服务列表。

ColorOS / OriginOS 清理后台时可能关闭无障碍服务。如果悬浮栏消失，回主界面看服务状态，需要时重新开启。

## 使用

悬浮栏按钮：

| 按钮 | 作用 |
| --- | --- |
| ⠿ | 按住拖动悬浮栏；单击收起为小圆钮（单击圆钮展开） |
| 方案名 ▾ | 选择方案 / 新建方案 |
| ▶ | 直接运行：从第 1 步开始按循环设置执行 |
| ⚡ / ✕ | 进入待命 / 退出待命（方案有触发点时可用） |
| ■ | 停止 |
| ✎ | 编辑当前方案的位置 |
| ≡ | 打开主界面 |

**音量下键**在运行中是急停键。

### 新建方案

1. 打开目标应用，在悬浮栏点"方案名 ▾ → ＋ 新建方案"（或在主界面新建后点 ✎）；
2. 编辑栏：`+` 加一个步骤、`−` 删掉最后一步、`+T / −T` 添加或移除触发点、`保存`、`取消`；
3. 拖动带编号的圆点到要点击的位置，**十字中心**就是点击点；红色的 "T" 是触发点；
4. 单击圆点可以微调 X / Y 和等待时长；
5. 点"保存"。

在主界面点方案可以设置名称、循环次数（或无限）、轮间隔、触发后延迟、触发区半径，以及每一步的等待时长；也可以复制、重命名、删除方案。

### 直接运行

选好方案，点 ▶。步骤坐标如果落在悬浮栏上会拒绝运行，把悬浮栏拖开再试。

### 手动触发

1. 方案中需要有触发点（T）；
2. 点 ⚡ 进入待命，触发点周围出现红色半透明圆圈；
3. 亲手点圆圈里的目标按钮：App 会把这次点击补发给下层应用，然后自动执行后续步骤；
4. 执行完（或点 ■ 停止）后自动回到待命，可以再次触发；点 ✕ 退出待命。

触发点不计入循环：循环 3 次时，触发点只点 1 次，后续步骤执行 3 轮。补发会比直接点击多几十毫秒的延迟。

## 工作原理

- 点击通过无障碍服务的 `dispatchGesture()` 执行，所有悬浮窗（控制栏、标记、触发区）也由无障碍服务托管；
- 手动触发采用"拦截 → 补发"：触发区先接住你的点击，随即变为不可触摸，再在同一位置补发一次点击给下层应用，然后执行后续步骤。补发会比直接点击多几十毫秒；
- 方案保存在应用私有目录的 `schemes.json` 中。

## License

[MIT](LICENSE)
