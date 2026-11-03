# 请求字段对齐说明

> 依据：`docs/[91] request_kohcamp.qq.com_message.txt`（真机 `POST /game/rolelist`，HTTP/2，客户端 10.114.1028）
>
> 对应实现：`io.github.gdpl2112.HttpApi`、`io.github.gdpl2112.config.CampDevice`

## 一、为什么要对齐

营地网关对调用方的识别不止依赖 `token`，还会对请求头做**一致性校验**。以下四类问题会被判为非官方客户端：

1. **同源不一致**——同一 token 的请求，设备标识每次都在变；
2. **自相矛盾**——字段之间无法同时成立于同一台真机；
3. **不可能取值**——真机不会上报的值；
4. **重复字段**——同一语义的头部出现多种大小写形式。

本次改造针对上述四类逐一处理，其余字段严格照抄抓包。

## 二、字段映射表

### 传输与编码

| 头部 | 代码来源 | 取值 | 说明 |
|---|---|---|---|
| `content-type` | `HttpApi.Payload` | 按请求体类型 | JSON 用 `application/json`，proto 用 `application/proto`，表单用 `application/x-www-form-urlencoded` |
| `user-agent` | 常量 | `okhttp/4.9.1` | 真机 okhttp 版本，不可改成浏览器 UA |
| `accept-encoding` | 常量 | `gzip` | |
| `content-encrypt` | 常量 | 空串 | 真机以空串显式声明「本次请求体不加密」 |
| `accept-encrypt` | 常量 | 空串 | 真机以空串显式声明「不接受加密响应」 |
| `noencrypt` | 常量 | `1` | 与上面两项配套 |

### 客户端版本

| 头部 | 配置键 | 默认值 |
|---|---|---|
| `cclientversionname` | `clientVersionName` | `10.114.1028` |
| `cclientversioncode` | `clientVersionCode` | `2057979709` |
| `tinkerid` | `tinkerId`（留空自动推导） | `2057979709_64_0` |
| `cchannelid` | `channelId` | `10003391` |

> `tinkerid` 恒为 `<cclientversioncode>_64_0`。旧实现写死 `2057965009_64_0` 而版本号已升到 `2057965009`，虽恰好自洽，但一旦单独改版本号就会脱节；现改为由版本号推导。

### 系统与硬件

| 头部 | 配置键 | 默认值 | 说明 |
|---|---|---|---|
| `csystem` | `system` | `android` | |
| `csystemversionname` | `systemVersionName` | `12` | Android 12 |
| `csystemversioncode` | `systemVersionCode` | `31` | API 31，**必须与版本名对应** |
| `cisarm64` | `arm64` | `true` | |
| `csupportarm64` | `arm64` | `true` | |
| `cgzip` | `gzip` | `1` | |
| `cpuhardware` | `cpuHardware` | `mt6833` | 真实 SoC 型号（天玑 700），**不能写厂商代号** |

### 游戏上下文

| 头部 | 配置键 | 默认值 |
|---|---|---|
| `gameid` / `cgameid` / `ccurrentgameid` | `gameId` | `20001` |
| `gameareaid` | `gameAreaId` | `1` |
| `gameroleid` | `gameRoleId` | `0`（建议填当前登录角色 id） |
| `gameserverid` | `gameServerId` | `0`（建议填当前登录角色区服 id） |
| `gameusersex` | `gameUserSex` | `1` |

> 真机行为按「是否已选定角色」区分（见第三节对比）：
> 未选角色的 `/game/rolelist` 四项全为 `0`；选定角色后的请求携带**当前登录角色**的大区 / 角色 / 区服 / 性别。
> 本项目调用的接口均属后者，因此默认按已选角色配置。

### 账号态

| 头部 | 来源 | 说明 |
|---|---|---|
| `token` | `bind.json` | 抓包所得 token |
| `userid` | `bind.json` | 抓包所得 userid |
| `openid` | `device.json` | 账号级 openid，32 位大写十六进制，**必须固定** |
| `kohdimgender` | `device.json` | 营地资料性别，1=男 2=女 |
| `gameopenid` | `device.json` | 游戏侧 openid，**与 `openid` 不是同一个值**；选定角色后出现，留空则不下发 |

### 每请求变化项

| 头部 | 生成方式 |
|---|---|
| `crand` | `System.currentTimeMillis()`，13 位毫秒时间戳 |
| `traceparent` | `00-<32hex>-<16hex>-01`，每次请求 24 字节随机 |

### 可选 / 不可伪造项

| 头部 | 处理 |
|---|---|
| `istrpcrequest` | `device.json` 的 `trpc`，默认 `false`——两份真机抓包（`rolelist`、`isaccesstokenexpired`）均不含该头部 |
| `encodeparam` | **离线无法复算**。由客户端安全 SDK 按请求体实时生成，默认留空即不下发。发送错误值比不发送更容易被判定为异常，仅在确定可复用时才填 |

## 三、两次抓包对比

两份抓包来自同一账号、同一设备，差异全部由「是否已选定角色」决定，可据此确认哪些字段是常量、哪些是上下文。

| 头部 | `[91] /game/rolelist` | `[131] /sso/isaccesstokenexpired` | 结论 |
|---|---|---|---|
| `content-type` | `application/proto; charset=UTF-8` | `application/json; charset=UTF-8` | 按接口协议区分，不能统一 |
| 请求体 | 10 字节 protobuf | 72 字节 JSON | 同上 |
| `gameareaid` | `0` | `1` | 未选角色 / 已选角色的差异 |
| `gameroleid` | `0` | `2974907601` | 同上 |
| `gameserverid` | `0` | `1391` | 同上 |
| `gameusersex` | `0` | `1` | 同上 |
| `gameopenid` | **不存在** | `DDDDBB0D058EDD4EAC30A9CC46AE48DD` | 选定角色后才出现，且 ≠ `openid` |
| `openid` | `0B557AA9D02F7159A2DBA984E0445760` | 同一值 | 账号级常量 |
| `x-log-uid` | `98F70B3D-...` | 同一值 | 设备级常量 |
| `crand` | `1793715726726` | `1793717329253` | 每请求变化 |
| `traceparent` | `00-fb909df4...` | `00-03f193e8...` | 每请求变化 |
| `encodeparam` | 152 字符 | 168 字符 | **每请求变化**，印证无法复用 |
| `token` | `14OFXePbm...` | `-8bexmsd2...` | 两次抓包间已轮换 |
| `istrpcrequest` | 不存在 | 不存在 | 真机从不携带 |

关键结论：`gameopenid` 只在选定角色后出现，且与 `openid` 是两个不同的值——这是上一版实现判断错误的地方。

## 四、已修正的检测特征

| 问题 | 旧实现 | 现实现 |
|---|---|---|
| 重复头部 | `getRequestHeadersOld()` 同时写入 `cClientVersionCode` 与 `cclientversioncode`，两个值都会发出 | 全小写 + `LinkedHashMap` 覆盖，不再可能重复 |
| 设备标识漂移 | `gameOpenId` 每次请求随机 UUID | `openid` 取自 `device.json`，跨请求固定 |
| 伪造设备 id | 下发 `cDeviceId: 9c5c0c0c-c5c0-...`，真机不存在该头部，且取值规律明显 | 删除；改用真机确实携带的 `x-log-uid` |
| 不可能取值 | `cpuHardware: qcom`（厂商代号，非 SoC 型号） | `mt6833` |
| 版本滞后 | `10.112.0603` / `2057965009`，与抓包相差两个版本 | `10.114.1028` / `2057979709` |
| 缺少追踪头 | 无 `x-log-uid`、`traceparent` | 已补齐 |
| content-type 错配 | proto 请求体下发 `application/x-protobuf` | `application/proto; charset=UTF-8` |
| 跨域 Host 错配 | 统一写死 `Host: kohcamp.qq.com`，再对 `ssl.kohsocialapp.qq.com` 手工改写 | 不再下发 Host，由 HTTP 客户端按 URL 生成 |
| `gameopenid` 判断错误（第二轮比对修正） | 仅旧版服务下发，且直接复用 `openid` 的值 | 独立配置项，与 `openid` 分离；选定角色后的请求全部下发 |
| 角色上下文缺失（第二轮比对修正） | `gameareaid=1`、`gameusersex=0`、`gameroleid/gameserverid` 不存在 | 按已选角色场景配置 `areaid=1`、`usersex=1`，并补 `gameroleid`/`gameserverid` |
| 多余标记头（第二轮比对修正） | 恒发 `istrpcrequest: true`，两份真机抓包均无此头 | 默认不下发，保留 `trpc` 开关 |

## 五、使用与调整

1. 首次启动会自动生成 `data/device.json`，字段缺失时自动补齐并回写；
2. 修改后调用 `GET /bind/reload` 即可免重启生效；
3. 同一文件内的字段必须成组修改（版本三元组、Android 版本二元组）；
4. `openid` 建议替换为抓包中该 token 对应的真实值，其余字段保持默认即可。

### 字段取值从哪来

`device.json` 描述的是**安卓真机客户端**，不是运行服务端的 PC。**不要**把 Windows 的 CPU 型号、系统版本写进去，那会与 `csystem: android` 直接矛盾。

| 字段 | 取值来源 |
|---|---|
| `openid` | 抓包中的 `openid` 头部。必须与该 token 所属账号一致 |
| `gameOpenId` | 抓包中的 `gameopenid` 头部。与 `openid` 不同值，绑定「账号 + 已选角色」 |
| `logUid` | 抓包中的 `x-log-uid` 头部。设备级 UUID，同一台手机固定 |
| `cpuHardware` | 抓包中的 `cpuhardware`；或 `adb shell getprop ro.board.platform` |
| `systemVersionName` | 抓包中的 `csystemversionname`；或 `adb shell getprop ro.build.version.release` |
| `systemVersionCode` | 抓包中的 `csystemversioncode`；或 `adb shell getprop ro.build.version.sdk` |
| `clientVersionName/Code` | 抓包中的 `cclientversionname` / `cclientversioncode` |
| `tinkerId` | 留空自动推导为 `<clientVersionCode>_64_0` |
| `gameAreaId/gameRoleId/gameServerId/gameUserSex` | 抓包中已选角色后的 `gameareaid` / `gameroleid` / `gameserverid` / `gameusersex`；角色级接口在调用点按实际值覆盖 |
| `dimGender` | 抓包中的 `kohdimgender`，1=男 2=女 |
| `encodeParam` | 无法离线生成，留空即可 |

> 没有抓包时，直接使用默认值（取自真机 `POST /sso/isaccesstokenexpired`，即已选角色的常态场景）也能保持字段自洽；只有 `openid` / `gameOpenId` 与账号相关，建议填真实值。

## 六、能力边界

对齐头部只能消除**静态特征**层面的差异。服务端风控还包括：

- 请求频率与时间分布（`crand` 递增节奏、单 token 并发数）；
- 业务行为合理性（同一 token 短时间查询大量陌生 UID）；
- `encodeparam` 等无法离线生成的签名参数。

因此本说明不构成「不被检测」的保证，仅为降低误判概率。请遵循项目免责声明，控制调用频率，仅查询本人或已获授权账号的数据。

另需说明一处已知简化：`FORM` 变体（旧版 `ssl.kohsocialapp.qq.com` 服务）沿用新版头部集合，未按老 SDK 裁剪 `x-log-uid` / `traceparent`。该服务对未知头部不敏感，为避免影响现有可用链路暂不裁剪；如需极致对齐，可在 `HttpApi#getHeaders` 中按 `Payload.FORM` 分支处理。
