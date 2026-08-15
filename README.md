# PlayerMusic - Spigot 音乐播放插件

PlayerMusic 是一款功能丰富的 Spigot 插件，允许服务器管理员和玩家在 Minecraft 中通过自定义资源包播放外部音乐链接。它支持独立播放和音乐房间功能，为你的服务器带来独特的听觉体验。

## 目录

*   [主要功能](#主要功能)
*   [要求与依赖](#要求与依赖)
*   [安装步骤](#安装步骤)
*   [配置 (`config.yml`)](#配置-configyml)
*   [命令与权限](#命令与权限)
*   [工作原理简述](#工作原理简述)
*   [故障排除](#故障排除)
*   [作者](#作者)

## 主要功能

*   **文件夹音乐自动识别**: 将 `.ogg` 或 `.mp3` 文件放入音乐文件夹，插件自动扫描识别为可播放歌曲，无需手动配置 URL。MP3 会自动转换为 OGG 缓存后播放。
*   **专辑分类 (子文件夹)**: 音乐文件夹中的子文件夹会作为专辑分类，在 GUI 中先显示专辑列表，点进专辑查看该专辑的歌曲。
*   **动态资源包生成**:
    *   **独立模式**: 为每个播放请求动态生成一个包含单首音乐的资源包。
    *   **合并模式 (可选)**: 将音乐动态添加到服务器配置的基础资源包中，减少客户端切换资源包的频率。
*   **内置 HTTP 服务器**: 用于托管动态生成的资源包，无需外部Web服务器。
*   **音乐房间**:
    *   玩家可以创建音乐房间，并邀请其他玩家加入。
    *   房间创建者可以控制音乐的播放、切换和停止。
    *   房间内的所有成员将同步收听相同的音乐。
*   **图形用户界面 (GUI)**: 通过 `/bf gui` 命令打开一个简单的界面，方便选择音乐。
*   **权限管理**: 精细的权限节点控制各项功能的使用。
*   **播放日志文件**: 播放过程日志写入 `player.log`，不刷服务器控制台。
*   **音量控制**: 播放的音乐遵循客户端的“音乐”音量设置。

## 要求与依赖

*   **Minecraft 服务器**: Paper 26.2 (Minecraft 26.2) 或兼容的服务端。
*   **Java**: Java 25 或更高版本 (Minecraft 26.2 要求 Java SE 25)。
*   **依赖**:
    *   Gson (用于 JSON 处理)
    *   Apache Commons Codec (用于 SHA-1 哈希计算)
    *   mp3spi + tritonus (纯 Java MP3 解码)
    *   jVorbisEnc (纯 Java Vorbis 编码)

## 音乐搜索/下载（纯 Java 转换，无需 ffmpeg）

插件内置 **纯 Java 音频转换**，`/bf search`、`/bf download` 下载 MP3 后自动转为 OGG，**无需安装任何外部程序**（无需 ffmpeg），跨平台（Linux/Windows 均可用）。

*   MP3 解码：mp3spi（JLayer 封装，纯 Java）
*   OGG 编码：jVorbisEnc（Xiph libvorbis 的 Java 移植，纯 Java）

> 播放本地 `music` 文件夹的 `.ogg` / `.mp3` 文件同样无需任何额外安装（MP3 首次播放前会自动转换为 OGG 缓存，缓存位于 `plugins/PlayerMusic/.converted/`）。

## 安装步骤

1.  下载最新的 `PlayerMusic.jar` 文件。
2.  将 `PlayerMusic.jar` 文件放入你服务器的 `plugins` 文件夹中。
3.  启动或重启你的 Minecraft 服务器。
4.  插件将自动生成默认的配置文件 `plugins/PlayerMusic/config.yml`。

## 配置 (`config.yml`)

插件首次加载时会自动生成 `config.yml`。以下是主要配置项的说明：

```yaml
# PlayerMusic 配置文件

# 语言设置 (消息文案在 lang-<语言>.yml 文件中，如 lang-zh.yml)
language: "zh"

# HTTP 文件服务器设置
httpServer:
  enabled: true # 是否启用内置 HTTP 服务器以提供资源包 (true/false)
  port: 8123 # HTTP 服务器监听的端口,你需要修改此配置项为空闲的TCP端口
  publicAddress: "" # 服务器的公共IP地址或域名。如果留空，插件会尝试自动检测，但可能不准确。
                    # 对于外部访问，强烈建议手动设置此项，例如："your.server.ip" 或 "your.domain.com"
  servePath: "musicpacks" # 资源包在URL中的路径前缀，例如 http://<publicAddress>:<port>/musicpacks/pack.zip
  tempDirectory: "temp_packs" # 存储临时生成的资源包的子目录 (在插件数据文件夹内)
  maxDownloadSizeBytes: 0 # 单个音频文件下载的最大允许大小（字节）。0 表示无限制。例如: 10485760 (10MB)
  downloadConnectTimeoutMillis: 5000 # 下载音频文件时的连接超时时间（毫秒）
  downloadReadTimeoutMillis: 10000   # 下载音频文件时的读取超时时间（毫秒）
  musicRoomInactiveCleanupDelaySeconds: 600 # 音乐室在无人且无活动后自动关闭的延迟时间（秒）

# 资源包相关设置
resourcePack:
  packFormat: 88 # 资源包格式版本。Minecraft 26.2 (Paper 26.2) 对应 88。请根据你的服务器版本调整。
                # 26.1 -> 84, 1.21.11 -> 70, 1.21.5 -> 55, 1.21 -> 34, 1.20.5-1.20.6 -> 32
  description: "§bPlayerMusic §7音乐资源包" # 资源包的描述文本

# 基础资源包合并设置 (如果启用，插件会将音乐添加到此基础包中，而不是创建独立的音乐包)
baseResourcePack:
  enableMerging: false # 是否启用与基础资源包的合并模式 (true/false)
  fileName: "base_pack.zip" # 基础资源包的文件名，应放置在插件的数据文件夹内。
  promptMessage: "§6加载音乐资源包..." # 发送合并后的音乐资源包给玩家时显示的提示信息
  originalPackPromptMessage: "§6恢复默认资源包..." # 当停止音乐并恢复到原始基础包时显示的提示信息

# 音乐文件夹设置 (自动扫描文件夹内的 .ogg / .mp3 音乐文件，无需手动配置 URL)
musicFolder:
  enabled: true # 是否启用文件夹音乐自动识别 (true/false)
  path: "music" # 音乐文件夹路径，相对于插件数据文件夹 (plugins/PlayerMusic/music)
  recursive: true # 是否递归扫描子文件夹中的 .ogg / .mp3 文件 (true/false)
  item: "MUSIC_DISC_CAT" # 文件夹音乐在GUI中显示的物品材质 (区分大小写, 参考 Spigot Material 枚举)
  lore: # 文件夹音乐物品的描述文本列表 (支持颜色代码 '&'，<name> 会被替换为文件名，<url> 会被替换为本地文件地址)
    - "§7自动识别的文件夹音乐"
    - "§7文件: <name>"
```

## 语言文件 (lang-*.yml)

所有游戏内消息和 GUI 文案都保存在语言文件中，默认中文为 `lang-zh.yml`（插件数据文件夹 `plugins/PlayerMusic/` 下）。

*   通过 `config.yml` 的 `language` 配置项切换语言（例如 `language: "zh"` 对应 `lang-zh.yml`）。
*   首次启动插件会自动从 jar 生成 `lang-zh.yml`，可自由编辑其中的消息。
*   消息支持颜色代码 `&`，`<placeholder>` 会被替换为实际值。
*   `musicFolder.lore` 等列表类文案仍在 `config.yml` 中配置。
*   GUI 专辑相关的文案（`gui.allMusic`、`gui.backToAlbums`）也在语言文件中。

## 专辑分类 (子文件夹)

把 `.ogg` / `.mp3` 文件按子文件夹分组即可自动形成专辑分类，例如：

```
plugins/PlayerMusic/music/
├── 流行/
│   ├── song1.ogg
│   └── song2.ogg
├── 古典/
│   └── mozart.ogg
└── 单曲.ogg   ← 根目录，归入"全部音乐"
```

打开 `/bf gui` 时，如果存在子文件夹，会先显示**专辑列表**；点击专辑进入该专辑的歌曲；"全部音乐"项查看根目录 + 所有专辑的歌曲。子文件夹内还可再嵌套子文件夹（多层专辑）。


**重要**:
*   `resourcePack.packFormat`: 这个值非常重要，必须与你的服务器客户端版本兼容。请查阅 Minecraft Wiki 获取最新的资源包版本信息 (例如，Paper 26.2 / Minecraft 26.2 通常是 88)。
*   `httpServer.publicAddress`: 如果服务器在NAT网络后（例如家庭网络），留空或使用 `auto` 可能无法正确检测到公网IP。你需要手动配置为你的公网IP或域名，否则玩家可能无法下载资源包。确保配置的 `httpServer.port` 在防火墙和路由器上是开放的。
*   `baseResourcePack.enableMerging`: 如果设置为 `true`，你必须在 `plugins/PlayerMusic/` 目录下放置一个名为 `baseResourcePack.fileName` 指定的有效基础资源包文件。

## 命令与权限

以下是插件的主要命令及其对应的权限节点：

| 命令                                            | 描述                                           | 权限节点                             |
| :---------------------------------------------- | :--------------------------------------------- | :----------------------------------- |
| `/bf play <歌曲名 或 序号>`                       | 播放音乐文件夹中的歌曲。                           | `playermusic.play`               |
| `/bf stop`                                      | 停止当前为自己播放的音乐，或停止自己创建的房间音乐。 | `playermusic.stop`               |
| `/bf gui`                                       | 打开音乐选择GUI。                                | `playermusic.gui`                |
| `/bf createroom <歌曲名> <房间描述>`              | 创建一个音乐房间。                               | `playermusic.createroom`         |
| `/bf join <房间创建者名称>`                       | 加入一个已存在的音乐房间。                         | `playermusic.joinroom`           |
| `/bf start`                                     | (房间创建者) 开始播放当前房间设置的歌曲。          | `playermusic.room.start`         |
| `/bf roomplay <歌曲名>`                          | (房间创建者) 切换当前房间的歌曲。                 | `playermusic.roomplay`           |
| `/bf disbandroom`                               | (房间创建者) 解散自己创建的音乐房间。              | `playermusic.disbandroom`        |
| `/bf reload`                                    | 重载插件配置文件。                               | `playermusic.reload`             |
| `/bf rescan`                                    | 重新扫描音乐文件夹。                              | `playermusic.reload`             |
| `/bf info`                                      | 显示插件信息。                                   | `playermusic.info`               |

## 工作原理简述

1.  **资源包生成**:
    *   当玩家请求播放音乐时，插件会根据配置（独立模式或合并模式）动态生成一个临时的资源包。
    *   这个资源包包含一个 `sounds.json` 文件，用于定义新的声音事件，并将该事件指向要播放的音乐文件。
    *   音乐文件（.ogg 格式）会从提供的 URL 下载到服务器的临时存储中，然后打包进资源包。
2.  **HTTP 服务器**:
    *   插件内置的 HTTP 服务器会托管这个动态生成的资源包。
    *   服务器向玩家发送资源包的下载链接和 SHA-1 哈希值。
3.  **客户端应用**:
    *   玩家的 Minecraft 客户端下载并应用资源包。
    *   一旦资源包加载成功，服务器会指令客户端播放定义好的声音事件，从而播放音乐。
4.  **音乐房间**:
    *   音乐房间允许多个玩家同步收听。当房间创建者启动或更改音乐时，所有房间成员都会收到相应的资源包和播放指令。

## 故障排除

*   **音乐不播放/音量小**:
    *   检查服务器控制台是否有错误信息。
    *   确认客户端已成功接受并加载了资源包（通常会有提示）。
    *   检查游戏内的“音乐”音量滑块和“主音量”滑块是否已调高。
    *   确保音乐文件夹中的 `.ogg` 文件格式有效且完整。
*   **提示 "HTTP Server Disabled"**:
    *   检查 `config.yml` 中的 `httpServer.enabled` 是否为 `true`。
    *   检查 `httpServer.port` 是否被其他程序占用。
*   **提示 "Base Pack Missing" (合并模式下)**:
    *   确保 `config.yml` 中 `baseResourcePack.enableMerging` 为 `true` 时，`plugins/PlayerMusic/` 目录下存在 `baseResourcePack.fileName` 指定的基础资源包文件。
*   **玩家无法下载资源包**:
    *   确认 `config.yml` 中的 `httpServer.publicAddress` 设置正确（对于公网服务器，应为服务器的公网IP或域名）。
    *   确认 `httpServer.port` 已在服务器防火墙和路由器（如果适用）中开放。
*   **音乐文件夹中的歌曲未识别**:
    *   确认 `.ogg` / `.mp3` 文件已放入 `plugins/PlayerMusic/music/` 目录（或 `musicFolder.path` 指定的目录）。
    *   执行 `/bf rescan` 或 `/bf reload` 重新扫描音乐文件夹。
    *   检查 `config.yml` 中 `musicFolder.enabled` 是否为 `true`。
    *   首次放入 MP3 时插件会异步转换为 OGG（缓存在 `.converted/`），转换完成后自动入列，可稍等片刻或再次 `/bf rescan`。

## 作者

PlayerMusic 由 **ALingqing** 开发。

---

## 许可证
本项目采用 [知识共享署名-非商业性使用4.0 国际许可证]（https://creativecommons.org/licenses/by-nc/4.0/） 进行许可。
