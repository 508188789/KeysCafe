# libs — 第三方依赖

## libxposed API（必需）

模块的入口类继承 `io.github.libxposed.api.XposedModule`，编译时需要它的 `classes.jar`。

**这个 jar 没有放进仓库**（避免仓库变大、也避免转分发第三方产物），自行下载：

```bash
# 1. 下载 AAR
curl -L -o api-102.0.0.aar \
  https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar

# 2. 从 AAR 里取出 classes.jar
unzip -o api-102.0.0.aar classes.jar -d api102

# 3. 放到约定位置
mkdir -p libs/api102
mv api102/classes.jar libs/api102/classes.jar
```

最终目录结构：

```
libs/
└── api102/
    └── classes.jar
```

也可以放在任意位置，用环境变量指定：

```bash
API_JAR=/path/to/classes.jar bash build.sh
```

> **版本必须对上**：`module.prop` 里写的是 `minApiVersion=102` / `targetApiVersion=102`，
> 因此要用 **102.x** 的 API jar。用 101 或 100 会编译失败（`XposedInterface` 的方法签名不同）。

## 为什么不用 Gradle

`build.sh` 直接调 `aapt2` / `javac` / `d8` / `zipalign` / `apksigner`。理由：

- 模块只有一个 Activity、一个 Provider、三个 Xposed 元数据文件，Gradle 的复杂度用不上；
- 出问题时能精确知道是哪一步挂了，而不是在一堆 task 里找；
- CI 里不需要下载 Gradle 和它的插件生态，构建时间从几分钟降到几秒。
