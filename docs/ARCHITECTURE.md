# 技术细节

这份文档记录实现过程中的结构分析、踩过的坑和最终取舍。代码里每个类还有详细注释，
这里只写「为什么这么做」。

- [1. 目标应用的结构](#1-目标应用的结构)
- [2. 三个钩子分别做什么](#2-三个钩子分别做什么)
- [3. 抗混淆：一切按签名](#3-抗混淆一切按签名)
- [4. 配置怎么跨进程传递](#4-配置怎么跨进程传递)
- [5. 动画与性能](#5-动画与性能)
- [6. 坐标系与对齐验证](#6-坐标系与对齐验证)

---

## 1. 目标应用的结构

微信输入法 4.0.1 的键盘**不是**「一个按键一个 View」。反编译后（jadx）看到的结构：

| 角色 | 混淆名 | 说明 |
|---|---|---|
| KeyboardView | `plugin.hld.keyboard.selfdraw.o` | 整块键盘，继承 `AbstractC2012s` |
| ButtonDrawer | `plugin.hld.keyboard.selfdraw.e` | 负责画按键 |
| 按键模型 | `plugin.hld.keyboard.selfdraw.j` | 纯数据对象，不是 View |

`o.onDraw()` 里遍历当前布局的所有按键，对每个按键调 `ButtonDrawer.b(button)` 画出来。
按键的「按下 / 抬起」只是把按键对象内部的状态改一下（`j.d()` / `j.e()`），
没有任何可监听的回调。

这带来两个直接结论：

1. **任何特效都必须画在绘制过程里**，没有独立的按键 View 可以挂前景/背景。
2. **按压时机只能自己判断**，拦截 `dispatchTouchEvent` 后用矩形做命中测试。

`KeyboardView` 有 `getButtonDrawer()`，`ButtonDrawer` 有：

```
a(...)        初始化/布局
b(j)          画一个按键          ← 我们钩这个
c()           某个状态判断
d(Canvas)     整块键盘的绘制入口
```

---

## 2. 三个钩子分别做什么

### 钩子 1：`ButtonDrawer.b(j)` —— Key Motion + Key Color

```java
hook(drawKey).intercept(chain -> {
    Canvas c = (Canvas) fCanvas.get(chain.getThisObject());  // 从字段拿 canvas
    Rect r = rectOf(chain.getArg(0));                        // 取按键矩形
    if (动效需要缩放) { c.save(); c.scale(sx, sy, cx, cy); }
    try {
        return chain.proceed();        // 输入法照常画
    } finally {
        if (缩放) c.restore();
        Fx.drawKeyOverlay(c, key, r);  // 在键帽之上叠配色
    }
});
```

三个细节：

- **用 `finally` 而不是顺序语句**：输入法内部抛异常时如果没 restore，
  canvas 的变换栈会失衡，整个键盘会开始乱飘（而且这种 bug 很难复现）。
- **配色必须在 `proceed()` 之后画**：画之前会被键帽盖住。
- **配色在 `restore()` 之后画**：否则会跟着键帽一起缩放，看起来像「色块缩得比键帽快」。

### 钩子 2：`KeyboardView.draw(Canvas)` —— 帧起点 + Keyboard Color

```java
hook(kbDraw).intercept(chain -> {
    onFrameStart();          // 清空本帧按键表、推进动画时钟、读配置
    Object ret = chain.proceed();
    Fx.drawOverlay(canvas);  // 粒子 / 波纹画在整块键盘之上
    return ret;
});
```

把粒子层和按键层画在**同一个 canvas** 上，因此两者共用坐标系，不需要任何坐标换算——
这是「特效不错位」的根本原因。

### 钩子 3：`KeyboardView.dispatchTouchEvent(MotionEvent)` —— 命中测试

```java
hook(kbTouch).intercept(chain -> {
    onTouch(motionEvent);   // 只旁听
    return chain.proceed(); // 绝不吞事件
});
```

`onTouch` 里维护一个「当前按下的键」：

- `ACTION_DOWN`：用上一帧记录的所有按键矩形做 `Rect.contains`，命中的就是按下的键；
- `ACTION_MOVE`：手指滑出当前键的矩形 → 抬起旧键、重新命中新键（滑动输入时特效跟着走）；
- `ACTION_UP / CANCEL`：全部抬起。

**为什么用「上一帧画过的矩形」而不是实时算布局**：布局信息在输入法内部，取它需要
再反射一堆字段；而刚刚画过的东西一定是最新的，且天然与视觉位置一致。

---

## 3. 抗混淆：一切按签名

### 被 Kotlin 元数据骗了一道

jadx 里显示的方法名：

```
public final Rect getDrawRect()   →  dex 里其实叫 F()
public final Rect getHitRect()    →  dex 里其实叫 t()
public final String getDrawerType() → dex 里其实叫 u()
```

jadx 读 Kotlin 的 `@Metadata` 注解把名字「还原」成了可读形式，但这些名字在运行时
根本不存在。第一版模块按 `getDrawRect` 找方法，直接 `MethodNotFoundException`。

**教训：反射查找一律只看签名，不看名字。**

### 现在的判据

| 要找什么 | 判据 |
|---|---|
| 画一个按键的方法 | 1 个参数、返回 `void`、参数不是基本类型/数组/Canvas、参数类型带「无参返回 Rect」的方法 |
| 按键矩形 getter | 无参、返回 `android.graphics.Rect`；有多个时按面积采样挑 |
| drawerType getter | 无参返回 `String`，且返回值命中 `drawerMap.keySet()` |
| Canvas 字段 | 类型恰好是 `android.graphics.Canvas` 的第一个字段 |
| drawerMap 字段 | 类型恰好是 `java.util.Map` 的第一个字段 |

多个候选时怎么选：

- 「画按键」方法：取**名字最短**的（混淆名多为单字母，`lambda$draw$0` 这类更长）；
- 矩形 getter：**累计面积采样**，60 个按键后取平均面积小的那个（`drawRect` 是绘制区，
  `hitRect` 是触摸判定区，后者一定不小于前者）。

采样而不是直接选第一个，是因为不同版本里两个方法的声明顺序会变，
而「绘制区比判定区小」是结构性的、不会变的性质。

---

## 4. 配置怎么跨进程传递

模块 App（改设置）和微信输入法（跑特效）是两个进程。试过四条路，全部失败：

| 方案 | 结果 |
|---|---|
| `ContentResolver.call(自定义 provider)` | `Unknown authority` — Android 11+ 包可见性拦截。调用方不是我们，改不了它的 manifest 加 `<queries>` |
| 读模块 App 私有目录文件 | 权限放开到 `0644` + 目录 `0711` 也没用，SELinux 的 per-app MLS 分类（`s0:c189,c257...`）直接拒绝 |
| `getRemotePreferences()` | 只对框架自己管理的偏好生效，普通模块拿不到 |
| `openRemoteFile()` | `listRemoteFiles()` 返回空，同样拿不到 |

### 最终方案：`Settings.Global`

```java
Settings.Global.putString(resolver, "keyscafe_fx_config", json);   // 模块侧写
Settings.Global.getString(resolver, "keyscafe_fx_config");         // 输入法侧读
```

- 所有应用**可读**，不受包可见性限制；
- 不在 per-app 隔离的路径里，SELinux 不拦；
- 代价：写入需要 `WRITE_SECURE_SETTINGS`，这是签名级权限，只能 `pm grant`。

三个必须注意的点：

1. **Manifest 里必须先声明**这个权限，否则 `pm grant` 会静默失败（包里没有这个权限项，
   grant 找不到目标）。
2. **Android 16 上 shell 已经无权 grant**，必须 `su -c 'pm grant ...'`。
   报错长这样：`Neither user 2000 nor current process has ...`。
3. 写 `Settings.Global` 用普通 API 即可，不需要 `su`——只要权限已授予。

代码里保留了另外四条通道作为退路（`readRemoteFile` / `readRemote` / `readProvider` /
`readModuleFile`），按顺序尝试。它们在你这台机器上全部返回 null，但在不做 per-app 隔离的
ROM 上可能有用，留着不碍事。

---

## 5. 动画与性能

### 帧驱动

```java
Choreographer.postFrameCallback(cb) → cb 里 if (Fx.busy()) { view.invalidate(); kick(); }
```

`Fx.busy()` 返回 false 时**不再预约下一帧**——没有动画时模块对性能的影响是零，
连一次多余的重绘都没有。这是整个设计里最重要的一条：键盘是高频交互组件，
任何「常驻后台刷帧」的实现都会被人感知为耗电。

### 时间基准

统一用 `SystemClock.uptimeMillis()`，并且在**每次绘制和每次触摸**时都更新一次
（触摸可能早于下一帧绘制到达，不更新的话动画时间会偏）。

### 零分配

键盘一帧要画 40+ 个按键，如果每个按键每次分配对象，GC 会直接把帧率打下来。
所以：

- `Paint` / `Path` / `RectF` / 输出数组全部是复用字段；
- 渐变 `Shader` 按按键缓存（`IdentityHashMap<Object, ShaderCache>`），
  缓存键包含矩形四边，键位变了才重建；
- 粒子的坐标**不存**，每帧按 `x = x0 + vx·t` 实时算，省内存也避免累积误差；
- 粒子上限 160，超出从最老的丢；
- 每 700ms 清理一次过期粒子/波纹/废弃按键记录。

### 曲线

- 按下：`easeOutCubic`（起步快、收尾慢，手感自然）
- 松手回弹：`easeOutBack`，`c1 = 2.6`（会冲过终点再回来 = 弹一下）
- 果冻：`sin(p·3π) · (1-p)^2.2`（阻尼振荡）

---

## 6. 坐标系与对齐验证

所有特效用的都是**键盘 View 的本地坐标**——按键矩形的来源就是输入法自己算的那个
`drawRect`，所以对齐是结构性的，不需要「调偏移量」这种脆弱做法。

验证方式：打开 `dbg` 描边。

- 绿色 = 钩 `drawKey` 时取的矩形
- 红色 = 键盘 `draw` 之后取的矩形

两者完全重合，说明按键层与特效层看到的是同一套坐标：

```
Q bbox (13,1690,139,1887) size (126,197)    # 像素扫描得到的实际键帽范围
t = Rect(14,16 - 116,187)                    # 钩子取到的矩形
```

量级一致、位置吻合（前者是整个窗口的绝对坐标，后者是键盘 View 内的相对坐标）。

如果绿框本身就偏了，说明两个矩形 getter 选反了——这时把 `logcat` 里的
`drawRect getter = ...` 那行看一下 `sum0 / sum1` 的值。
