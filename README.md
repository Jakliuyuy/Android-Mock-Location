# 双模安卓模拟定位 (Android Mock Location)

> **工业级 Android 双模位置仿真与环境特征伪装引擎**  
> 支持 **Root 原生系统提权模式** 与 **Xposed / LSPosed 深度拦截模式**，结合三层防检测特征剥除、物理动力学轨迹平滑巡航、多维度基站与 WiFi 隔离，以及目标应用级精准白名单分流。

---

## 目录 (Table of Contents)

1. [项目简介与设计理念](#1-项目简介与设计理念)
2. [系统整体架构与拓扑图](#2-系统整体架构与拓扑图)
3. [双模式技术原理深度对比](#3-双模式技术原理深度对比)
4. [核心功能特性全景 (对照需求规范 §R1 - §R4)](#4-核心功能特性全景)
   - [§R1 客户端控制中心与交互](#r1-客户端控制中心与交互)
   - [§R2 双模核心定位拦截引擎](#r2-双模核心定位拦截引擎)
   - [§R3 三层深度防检测与环境特征伪造](#r3-三层深度防检测与环境特征伪造)
   - [§R4 路线规划与物理学仿真巡航](#r4-路线规划与物理学仿真巡航)
5. [Root 原生提权模式部署与使用指南](#5-root-原生提权模式部署与使用指南)
6. [Xposed / LSPosed 模式部署与配置指南](#6-xposed--lsposed-模式部署与配置指南)
7. [动态轨迹动力学与数学物理公式](#7-动态轨迹动力学与数学物理公式)
   - [7.1 大圆航线球面插值 (Haversine & Slerp)](#71-大圆航线球面插值-haversine--slerp)
   - [7.2 自回归 AR-1 速度抖动模型](#72-自回归-ar-1-速度抖动模型)
   - [7.3 Ornstein-Uhlenbeck 均值回归有界微漂移模型](#73-ornstein-uhlenbeck-均值回归有界微漂移模型)
8. [控制中心 UI 与地图操作手册](#8-控制中心-ui-与地图操作手册)
9. [工程构建与测试验证手册](#9-工程构建与测试验证手册)
10. [源代码目录树与模块说明](#10-源代码目录树与模块说明)
11. [免责与合规声明](#11-免责与合规声明)

---

## 1. 项目简介与设计理念

在移动应用测试、地理信息系统开发验证、LBS 应用边界演练及个人位置隐私保护场景中，常规的“开发者选项 - 模拟位置”极易被目标应用通过底层 API 检出（如检测 `isFromMockProvider` 标志、读取基站小区 ID 或扫描周边物理 WiFi 热点）。

**双模安卓模拟定位** 旨在提供全链路、系统级的真实位置仿真解决方案：
- **双模架构兼容**：未安装 Hook 框架的设备可直接使用 **Root 原生提权模式**，自动赋予 AppOps 权限并接管系统 TestProvider；已装配 LSPosed/Xposed 框架的设备可启用 **深度 Hook 拦截模式**，在目标应用进程内实现内存级坐标透明替换。
- **三重防检测剥除**：彻底解决 Android 8.0 - 15.0 中各版本 AOSP 位置对象特征泄露问题（反射抹除 `Location.mFieldsMask` 位图、置空 `isFromMockProvider`、净化 `mExtras`）。
- **物理动力学巡航**：告别生硬的直线跳跃，结合 Haversine 球面大圆几何、一阶自回归 AR-1 速度平滑扰动与 Ornstein-Uhlenbeck (OU) 均值回归随机微漂移，完美复现真实行人和车辆运动的物理特征。
- **纯净白名单分流**：严格的 Opt-in 隔离机制，仅对用户显式勾选的目标应用生效拦截，主机其他系统应用与网络服务完全不受干扰。

---

## 2. 系统整体架构与拓扑图

系统采用 Clean Architecture 架构分层，实现核心领域数学/物理算法与 Android Framework / Xposed 运行时的完全解耦：

```text
+-----------------------------------------------------------------------------------------+
|                                表现层与控制中心 (Presentation)                              |
|   +---------------------------------------------------------------------------------+   |
|   |   MainActivity (状态面板 / 实时经纬度 / 速度预设 / 模式切换)                        |   |
|   |   OsmMapController (OSMDroid 瓦片底图 / 触控点选 / 路线规划点序列标记)              |   |
|   |   WhitelistActivity (PackageManager 包扫描 / 用户应用筛选 / 多选状态持久化)         |   |
|   +---------------------------------------------------------------------------------+   |
+--------------------------------------------+--------------------------------------------+
                                             | 广播通知 / 本地配置注入
                                             v
+-----------------------------------------------------------------------------------------+
|                                  跨进程数据总线 (IPC Bridge)                              |
|   +---------------------------------------------------------------------------------+   |
|   |   LocationConfigProvider (ContentProvider 跨进程安全共享，URI 权威解析)             |   |
|   |   LocationUpdateReceiver (Broadcast 毫秒级坐标热分发与巡航心跳驱动)                 |   |
|   |   MockConfigManager (目标进程内部双缓冲配置缓存，内存无锁读取)                      |   |
|   +---------------------------------------------------------------------------------+   |
+---------------------+---------------------------------------------------+---------------+
                      |                                                   |
                      v                                                   v
+-------------------------------------------+   +-----------------------------------------+
|        模式 A: Root 原生提权引擎          |   |       模式 B: Xposed / LSPosed 拦截     |
|  +-------------------------------------+  |   |  +-----------------------------------+  |
|  |  RootProviderManager                |  |   |  |  XposedInit (LSPosed 模块加载入口)  |  |
|  |  ShellUtil (su appops mock_location)|  |   |  |  LocationHook (Framework API 拦截)|  |
|  |  LocationManager.addTestProvider    |  |   |  |  NetworkAntiDetectHook (基站/WiFi)|  |
|  |  LocationManager.setTestLocation    |  |   |  |  MockLocationCleaner (三层特征脱敏)|  |
|  +-------------------------------------+  |   |  +-----------------------------------+  |
+-------------------------------------------+   +-----------------------------------------+
                                             ^                                   ^
                                             |                                   |
+--------------------------------------------+-----------------------------------+--------+
|                                领域核心引擎与算法层 (Domain Core)                        |
|   +---------------------------------------------------------------------------------+   |
|   |  com.mockloc.domain.math.SpatialMath:                                           |   |
|   |    - Haversine 球面大圆距离 (WGS84 半径 6,371,000m)                               |   |
|   |    - 大圆初始航向角 (Initial Bearing 0° - 360°)                                 |   |
|   |    - 球面大圆线性插值 (Slerp)                                                    |   |
|   |                                                                                 |   |
|   |  com.mockloc.domain.engine.MotionSimulator:                                     |   |
|   |    - AR-1 一阶自回归速度噪声模型 (v_t = α * v_{t-1} + (1-α)*v_base + ε)          |   |
|   |    - Ornstein-Uhlenbeck 平稳均值回归微漂移 (半径约束 <= 4.0 米)                   |   |
|   |                                                                                 |   |
|   |  com.mockloc.domain.engine.CruiseEngine:                                        |   |
|   |    - 路线状态机管理 (STOPPED, RUNNING, PAUSED, COMPLETED)                         |   |
|   |    - 循环控制策略 (ONE_WAY, PING_PONG, LOOP)                                    |   |
|   |                                                                                 |   |
|   |  com.mockloc.domain.geocoding.OfflineFallbackGeocodingService:                  |   |
|   |    - 内置 55+ 全国核心地标/城市离线数据库 + Nominatim 在线逆解析兜底              |   |
|   +---------------------------------------------------------------------------------+   |
+-----------------------------------------------------------------------------------------+
```

---

## 3. 双模式技术原理深度对比

| 评估维度 | Root 原生提权模式 (Root Native Mode) | Xposed / LSPosed 深度拦截模式 (Hook Mode) |
| :--- | :--- | :--- |
| **工作原理** | 通过 `su` 执行命令授权 AppOps，利用系统原生的 `TestProvider` 注入伪造的 GPS Provider 坐标 | 依托 Zygote 注入目标应用进程，在内存层面 Hook 拦截 `LocationManager` 与基站/WiFi 接口 |
| **适用环境** | 适用于仅刷入 Magisk / KernelSU / APatch，未激活 Xposed/LSPosed 的纯 Root 设备 | 适用于安装并激活了 LSPosed / EdXposed / Xposed 框架的 Root 或定制设备 |
| **生效范围** | 系统级统一伪造（所有监听 GPS_PROVIDER 的应用均收到 TestProvider 坐标） | **目标应用级精准隔离**（仅对在控制中心勾选并加入 LSPosed 作用域的目标应用生效） |
| **防检测表现** | **中**：虽然由系统分发，但若目标应用直接调用 `isFromMockProvider`，由于是 TestProvider，系统仍可能打上标记 | **极高**：通过三层反射彻底抹去 `FLAG_MOCKED` 位图，`isFromMockProvider()` 强制返回 false，同时阻断基站/WiFi |
| **周边网络欺骗** | 无法直接伪造/阻断基站与 WiFi 扫描（目标应用可通过网络基站辅助定位穿透） | **全链路伪造**：阻断真实物理基站 `CellInfo` 泄露，并将连接的 WiFi BSSID 替换为脱敏安全掩码 |
| **开发便利性** | 即开即用，无需配置复杂的框架作用域 | 即插即用，通过跨进程 ContentProvider 动态热重载，无需重启设备 |

---

## 4. 核心功能特性全景

### §R1. 客户端控制中心与交互
- **地图视图与精确选点**：集成 OSMDroid 开源瓦片地图，支持平移、双指缩放、单击精准落点、长按路线途径点标定。
- **全方位空间参数调节**：支持实时设定目标纬度（Latitude）、经度（Longitude）、海拔高度（Altitude）、定位精度（Accuracy）及运动航向角（Bearing）。
- **巡航速度快捷预设**：
  - 🚶 **步行模式**：基准 1.2 m/s (~4.32 km/h)
  - 🚲 **骑行模式**：基准 4.5 m/s (~16.20 km/h)
  - 🚗 **驾车模式**：基准 12.0 m/s (~43.20 km/h)
  - ⚙️ **自定义模式**：支持 0.1 ~ 50.0 m/s 任意速率输入。
- **目标应用白名单分流**：内置基于 `PackageManager` 的应用选择器，自动过滤系统冗余进程，提供检索与多选持久化。
- **双模环境感知指示器**：实时探针检测当前设备 Root 授权可用性及 Xposed 模块激活状态，直观反馈运行就绪度。

### §R2. 双模核心定位拦截引擎
- **Hook 深度透明伪装**：
  - 挂载 `LocationManager.getLastKnownLocation(String)`
  - 挂载 `LocationManager.getCurrentLocation(...)` (Android 11+ / 12+)
  - 挂载 `LocationManager.requestLocationUpdates(...)`（拦截各类变体重载）
  - 挂载 `LocationManager.requestSingleUpdate(...)`
  - 动态代理（Dynamic Proxy）劫持传入的 `LocationListener`，在 `onLocationChanged(Location)` 回调前强制完成参数清洗与拟真坐标注入。
- **Root 原生提权注入**：
  - 自动化执行 `appops set <package> android:mock_location allow`
  - 规范管理 `TestProvider` 完整生命周期（`addTestProvider` -> `setTestProviderEnabled` -> `setTestProviderStatus` -> `setTestProviderLocation`）。

### §R3. 三层深度防检测与环境特征伪造
1. **API 层拦截**：
   - 挂载 `Location.isFromMockProvider()` 强制返回 `false`。
   - 挂载 `Location.isMock()` (API 31+) 强制返回 `false`。
2. **底层 Bitmask 反射剥除**：
   - AOSP `Location.java` 内部通过私有整型 `mFieldsMask` 记录标志位，其中第 4 位为 `HAS_MOCK_PROVIDER_MASK = (1 << 4) = 16`。
   - `MockLocationCleaner` 通过反射提取并执行 `mFieldsMask = mFieldsMask and 16.inv()`，从底层二进制位层面抹杀模拟标记。
   - 递归反射扫描并置空旧版本 Android (4.x - 7.x) 的 `mIsFromMockProvider` 私有字段。
3. **Extras 元数据净化**：
   - 剥除 Location 携带的 `Bundle` 中的 `"mockLocation"`, `"mock"`, `"isFromMockProvider"` 键值。
4. **基站 (CellInfo) 防穿透**：
   - 拦截 `TelephonyManager.getAllCellInfo()`、`getCellLocation()` 及 `requestCellInfoUpdate()`，对白名单应用强制返回空集合，防止应用利用基站数据库逆向推算真实地理位置。
5. **WiFi 扫描防穿透与 BSSID 脱敏**：
   - 拦截 `WifiManager.getScanResults()` 与 `startScan()` 返回空列表。
   - 拦截 `WifiManager.getConnectionInfo()`，反射重写 `WifiInfo.mBSSID` 为全球安全虚拟地址 `02:00:00:00:00:00`，彻底斩断 WiFi 辅助定位指纹。

### §R4. 路线规划与物理学仿真巡航
- **多点有序路线解析**：支持起点、多个连续途径点及终点的顺序解析与合法性校验，自动计算整条折线各分段的大圆测地线距离。
- **三种循环巡航模式**：
  - `ONE_WAY`：沿规划路线由起点行进至终点后平稳停止。
  - `PING_PONG`：抵达终点后自动掉头逆序巡航，往复穿梭。
  - `LOOP`：抵达终点后由闭环航线平滑返回起点，周而复始。
- **拟真物理动力学**：
  - 基于 AR-1 速度噪声与 Ornstein-Uhlenbeck 随机过程微漂移，有效对抗各类基于加速度计与速度导数反作弊算法的步频检测。

---

## 5. Root 原生提权模式部署与使用指南

### 5.1 前置条件
1. 目标 Android 设备已通过 **Magisk**、**KernelSU** 或 **APatch** 取得最高超级用户（Root）权限。
2. 设备系统版本：Android 8.0 (API 26) ~ Android 15 (API 35)。

### 5.2 运作流程
1. 进入应用主界面，顶部状态栏将自动调用 `su -c id` 检测超级用户环境。
2. 在模式选择开关中切换至 **"Root 原生模式"**。
3. 应用将自动调用系统提权命令为当前应用授予模拟定位权限：
   ```bash
   su -c appops set com.mockloc android:mock_location allow
   ```
4. 在地图中选定目标坐标或规划巡航路线后，点击 **"开启模拟"**。
5. `RootProviderManager` 将在系统内部注册合法的虚拟 GPS 提供者（`LocationManager.GPS_PROVIDER`），并将计算好的连续时间戳坐标序列推送至 Android Framework 核心服务。

---

## 6. Xposed / LSPosed 模式部署与配置指南

### 6.1 前置条件
1. 设备已安装并成功激活 **LSPosed** 框架（推荐使用 Zygisk 模式）。
2. 在 LSPosed 管理器中已安装本应用（`app-debug.apk`）。

### 6.2 模块作用域配置 (Scope Setup)
为保证系统稳定与精准隔离，**切勿勾选“系统框架”或无辜的三方应用**：
1. 打开 **LSPosed Manager** 应用。
2. 在“模块”列表中找到 **"双模安卓模拟定位"**。
3. 开启模块激活开关。
4. **关键步骤**：在“作用域”列表中，仅勾选需要进行定位伪装的目标应用（例如各类打卡软件、社交分享软件或被测应用）。
5. 重启目标应用进程（通过“强行停止”或后台划卡关闭）。

### 6.3 跨进程实时通信与热重载
- 本项目设计了基于 `LocationConfigProvider` 的无缝跨进程 IPC 体系。
- 当您在控制中心更新坐标、切换速度或启闭开关时，配置将通过系统 ContentProvider 广播至目标应用内部的 `MockConfigManager`。
- **热同步延时 < 10ms**，目标应用在运行过程中即可瞬间响应最新坐标，**完全无需重启手机或重新开关 LSPosed 模块**。

---

## 7. 动态轨迹动力学与数学物理公式

### 7.1 大圆航线球面插值 (Haversine & Slerp)

地球表面两点间的最短路径为大圆航线（Great Circle Path）。设定地球平均半径 $R = 6,371,000\text{ m}$。

#### 1) Haversine 测地距离公式
对于起点 $P_1(\phi_1, \lambda_1)$ 与终点 $P_2(\phi_2, \lambda_2)$（其中 $\phi$ 为弧度制纬度，$\lambda$ 为弧度制经度）：
$$\Delta\phi = \phi_2 - \phi_1,\quad \Delta\lambda = \lambda_2 - \lambda_1$$
$$a = \sin^2\left(\frac{\Delta\phi}{2}\right) + \cos(\phi_1)\cos(\phi_2)\sin^2\left(\frac{\Delta\lambda}{2}\right)$$
$$c = 2 \cdot \operatorname{atan2}\left(\sqrt{a}, \sqrt{1-a}\right)$$
$$d = R \cdot c$$

#### 2) 大圆初始方位角 (Initial Bearing)
航向角 $\theta \in [0^\circ, 360^\circ)$ 计算如下：
$$y = \sin(\Delta\lambda)\cos(\phi_2)$$
$$x = \cos(\phi_1)\sin(\phi_2) - \sin(\phi_1)\cos(\phi_2)\cos(\Delta\lambda)$$
$$\theta = \left(\operatorname{atan2}(y, x) \cdot \frac{180}{\pi} + 360\right) \pmod{360}$$

#### 3) 球面线性插值 (Slerp)
当进度比例为 $f \in [0.0, 1.0]$ 时：
$$A = \frac{\sin((1-f)c)}{\sin(c)},\quad B = \frac{\sin(f \cdot c)}{\sin(c)}$$
$$x = A\cos(\phi_1)\cos(\lambda_1) + B\cos(\phi_2)\cos(\lambda_2)$$
$$y = A\cos(\phi_1)\sin(\lambda_1) + B\cos(\phi_2)\sin(\lambda_2)$$
$$z = A\sin(\phi_1) + B\sin(\phi_2)$$
$$\phi_f = \operatorname{atan2}\left(z, \sqrt{x^2 + y^2}\right),\quad \lambda_f = \operatorname{atan2}(y, x)$$

---

### 7.2 自回归 AR-1 速度抖动模型

为消除机械式匀速巡航的作弊指纹，系统引入带有一阶自回归衰减的高斯平滑速度噪声：

$$v_t = \alpha \cdot v_{t-1} + (1 - \alpha) \cdot v_{\text{base}} + \varepsilon_t$$

- $\alpha = 0.85$：自回归惯性衰减因子，保证加速度连续可导，避免出现超自然加减速。
- $v_{\text{base}}$：用户设定的巡航目标速度。
- $\varepsilon_t \sim \mathcal{N}\left(0, (0.08 \cdot v_{\text{base}})^2\right)$：标准高斯白噪声。
- 物理边界硬约束：$v_t \in [0.5 \cdot v_{\text{base}}, 1.5 \cdot v_{\text{base}}]$。

---

### 7.3 Ornstein-Uhlenbeck 均值回归有界微漂移模型

真实 GPS 芯片在静止或匀速运动中，受电离层折射、多径效应及天线热噪声影响，坐标必定产生围绕中心点的微小布朗漂移。如果坐标完全定死，极易被风控 SDK 的静止检测策略捕捉。

系统通过 **Ornstein-Uhlenbeck (OU) 随机微分方程** 构建具有弹性回归特性的连续漂移：

$$d\mathbf{x}_t = -\theta \mathbf{x}_t \, dt + \sigma \, d\mathbf{W}_t$$

在切平面局部坐标系 $(x, y)$（单位：米）中，采用 Euler-Maruyama 离散化求解：
$$x_{t+\Delta t} = x_t - \theta \cdot x_t \cdot \Delta t + \sigma \cdot \sqrt{\Delta t} \cdot \xi_x$$
$$y_{t+\Delta t} = y_t - \theta \cdot y_t \cdot \Delta t + \sigma \cdot \sqrt{\Delta t} \cdot \xi_y$$

- 均值回归速率 $\theta = 0.5$：提供向轨迹中心点的弹性拉力，防止坐标无限发散。
- 扩散系数 $\sigma = 0.3$：控制微漂移扰动方差。
- $\xi_x, \xi_y \sim \mathcal{N}(0, 1)$：相互独立的标准正态随机变量。
- **严格有界性**：漂移向量模长限制 $\sqrt{x^2 + y^2} \le 4.0\text{ m}$，若超出则向径向内投影截断。

---

## 8. 控制中心 UI 与地图操作手册

### 8.1 界面布局与交互
- **顶部系统状态栏**：
  - `Root 权限状态`：展示当前设备是否具备 SU 提权。
  - `LSPosed 模块状态`：展示当前进程是否已装载 Hook 引擎。
  - `模式切换 Chip`：点击在 **Root 原生提权** 与 **LSPosed 深度 Hook** 间无缝切换。
- **地图操作区**：
  - **单点打点**：轻触地图任意位置，红点即刻移动，主界面经纬度实时刷新。
  - **长按规划航线**：长按地图添加途径点（Waypoint），地图绘制蓝色大圆引导轨迹线。
  - **实时地名搜索**：输入关键字（如“东方明珠”、“天安门”），双模检索（支持离线 55+ 核心地标秒级匹配与在线 Nominatim 引擎自动回退）。
- **参数控制面板**：
  - 手动微调经度、纬度、高度（米）、精度（米）、方位角（度）。
  - 选择预设巡航速度（步行/骑行/驾车）或滑块精准自定义。
  - 循环模式切换：单向 (One-Way) / 往返 (Ping-Pong) / 闭环 (Loop)。
- **底栏控制按钮**：
  - `开启模拟 / 停止模拟`：一键激活/释放全链路拦截引擎。
  - `暂停巡航 / 继续巡航`：保持当前坐标不动并保持心跳。
  - `白名单配置`：点击拉起应用勾选清单，即刻配置隔离应用。

---

## 9. 工程构建与测试验证手册

### 9.1 构建环境依赖
- **操作系统**：Linux / macOS / Windows
- **Java SDK**：OpenJDK 17 (`JAVA_HOME` 指向 JDK 17)
- **Android SDK**：API Level 35, Build-Tools 35.0.0
- **构建工具**：Gradle 8.11.1, Android Gradle Plugin (AGP) 8.9.2, Kotlin 2.0.21

### 9.2 运行全套自动化测试
项目包含全面的单元测试套件，全面覆盖数学几何、运动引擎、清理器脱敏、Hook 拦截、Root 提权及地名解析：

```bash
./gradlew test -Pkotlin.incremental=false --rerun-tasks
```

执行结果验证：
- 覆盖率统计：包含 162+ 项自动化单元测试用例。
- 验收标准：**100% 测试通过，0 failures, 0 errors, 0 ignored**。
- HTML 报告路径：`app/build/reports/tests/testDebugUnitTest/index.html`。

### 9.3 编译生产/调试 APK 产物
执行全新洁净构建：

```bash
./gradlew assembleDebug -Pkotlin.incremental=false
```

- **生成产物路径**：`app/build/outputs/apk/debug/app-debug.apk`
- **产物文件大小**：约 6.4 MB
- **APK 结构完整性校验**：
  ```bash
  # 验证 DEX 文件与关键资源
  unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "classes.*dex|assets/xposed_init|activity_main"
  
  # 验证 Xposed 入口文件声明
  unzip -p app/build/outputs/apk/debug/app-debug.apk assets/xposed_init
  # 输出: com.mockloc.hook.XposedInit
  ```

---

## 10. 源代码目录树与模块说明

```text
/root/模拟定位/
├── gradlew                               # Linux/Unix Gradle Wrapper 引导程序
├── gradlew.bat                           # Windows Gradle Wrapper 引导程序
├── gradle/wrapper/                       # Gradle 8.11.1 依赖包装器
├── build.gradle.kts                      # 根工程构建脚本 (AGP 8.9.2, Kotlin 2.0.21)
├── settings.gradle.kts                   # 工程子项目与仓库源设定
├── gradle.properties                     # JVM 构建内存与 Kotlin 特性参数
├── local.properties                      # 本地 Android SDK 路径定位
├── README.md                             # 生产级综合工程使用与架构说明文档
├── PROJECT.md                            # 架构设计与里程碑交付规范
└── app/
    ├── build.gradle.kts                  # 应用级依赖、插件与编译参数
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml       # 清单文件 (含 xposedmodule/description 等元数据)
        │   ├── assets/
        │   │   └── xposed_init           # Xposed 框架加载引导标识
        │   ├── java/com/mockloc/
        │   │   ├── domain/
        │   │   │   ├── model/            # 核心数据模型 (GeoPoint, Waypoint, PlannedRoute)
        │   │   │   ├── math/             # 空间球面数学几何 (SpatialMath)
        │   │   │   ├── engine/           # 巡航动力学引擎 (CruiseEngine, MotionSimulator)
        │   │   │   └── geocoding/        # 地理编码体系 (GeocodingService, 离线 55+ 数据库)
        │   │   ├── hook/
        │   │   │   ├── XposedInit.kt     # LSPosed 模块入口
        │   │   │   ├── LocationHook.kt   # LocationManager 及监听器深度拦截
        │   │   │   ├── NetworkAntiDetectHook.kt # 基站与 WiFi 扫描隔离过滤器
        │   │   │   └── cleaner/
        │   │   │       └── MockLocationCleaner.kt # 三层特征剥离清洗核心
        │   │   ├── root/
        │   │   │   ├── RootProviderManager.kt   # Root TestProvider 注册与管理
        │   │   │   └── ShellUtil.kt             # SU 执行器与 AppOps 提权工具
        │   │   ├── data/
        │   │   │   ├── MockConfigManager.kt     # 内存双缓冲全局配置管理器
        │   │   │   └── WhitelistRepository.kt   # 白名单多选与持久化存储
        │   │   ├── presentation/
        │   │   │   ├── MainActivity.kt          # 控制中心主界面
        │   │   │   ├── WhitelistActivity.kt     # 白名单应用选择界面
        │   │   │   └── map/OsmMapController.kt  # OSMDroid 地图控制器
        │   │   └── ipc/
        │   │       ├── LocationConfigProvider.kt# 跨进程 ContentProvider
        │   │       └── LocationUpdateReceiver.kt# 坐标更新广播接收器
        │   └── res/
        │       ├── layout/                      # 界面布局 (activity_main, activity_whitelist 等)
        │       └── values/                      # 字符串、色彩与样式定义
        └── test/java/com/mockloc/               # 完整单元测试套件 (162+ 用例)
            ├── domain/math/                     # 球面距离、方位角、插值单元与对抗测试
            ├── domain/engine/                   # 巡航动力学、步进、AR-1、OU 漂移测试
            ├── domain/geocoding/                # 地理编码模型与离线地标测试
            ├── domain/model/                    # 路线序列与点位模型测试
            ├── hook/                            # LocationHook 补丁、基站与 WiFi 防穿透测试
            ├── hook/cleaner/                    # Mock 标志抹除、反射清洗深度测试
            ├── root/                            # Root 命令构造、安全异常与生命周期测试
            └── data/                            # 白名单过滤与配置持久化测试
```

---

## 11. 免责与合规声明

1. **合法合规原则**：本项目仅供移动应用开发者用于自身的地理位置服务功能测试、应急容灾演练、定位鲁棒性验证以及个人合法隐私保护用途。
2. **禁止非法使用**：严禁将本软件或相关技术用于任何违反国家法律法规、侵犯他人合法权益或违背第三方服务条款的行为（包括但不限于虚假打卡作弊、非法越界、欺诈牟利等）。
3. **免责条款**：使用者因不当使用或违反相关规范所产生的一切直接或间接法律责任与后果，均由使用者本人独立承担，开发者与贡献者不承担任何连带责任。
