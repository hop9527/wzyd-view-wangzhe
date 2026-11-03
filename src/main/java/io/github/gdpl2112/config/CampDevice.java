package io.github.gdpl2112.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import io.github.kloping.file.FileUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * 王者营地客户端「设备指纹」配置。
 *
 * <p>营地网关会按请求头对调用方做一致性校验，同一 token 的请求如果出现下列情况，
 * 很容易被判定为非官方客户端：</p>
 * <ol>
 *     <li>设备标识每次请求都在变化（如每次生成新的 openid / 设备 id）；</li>
 *     <li>字段之间自相矛盾（如客户端版本号与 tinkerId 前缀不一致）；</li>
 *     <li>使用了真实设备不可能上报的取值（如 SoC 写成厂商代号 {@code qcom}）；</li>
 *     <li>同时出现同一字段的多种大小写形式，导致重复头部。</li>
 * </ol>
 *
 * <p>因此本类把与「设备」强相关的字段固化为 {@code data/device.json}：
 * 首次启动写入默认值并落盘，之后每次请求复用同一份指纹。默认值取自真机抓包
 * （{@code docs/[91] request_kohcamp.qq.com_message.txt}）。</p>
 *
 * <p><b>修改约定</b>：同一组字段必须保持自洽，否则比不改更危险。</p>
 * <ul>
 *     <li>{@code clientVersionName} / {@code clientVersionCode} / {@code tinkerId}
 *     三者必须指向同一个客户端版本；{@code tinkerId} 留空时由版本号自动推导；</li>
 *     <li>{@code systemVersionName} / {@code systemVersionCode} 必须对应同一个 Android 版本；</li>
 *     <li>{@code cpuHardware} 必须是真实 SoC 型号（如 {@code mt6833}、{@code sm8250}），不能写厂商代号。</li>
 * </ul>
 *
 * @author github kloping
 */
@Slf4j
@Component
public class CampDevice {

    /** 设备指纹文件名，位于 {@code data.path} 目录下 */
    private static final String FILE_NAME = "./device.json";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    @Value("${data.path:./data}")
    public String datapath = "./data";

    private volatile Profile profile;

    /**
     * 获取当前设备指纹，首次调用时从磁盘加载并补齐缺失字段。
     *
     * @return 设备指纹，进程内保持同一实例
     */
    public Profile getProfile() {
        Profile p = profile;
        if (p == null) {
            synchronized (this) {
                if (profile == null) profile = load();
                p = profile;
            }
        }
        return p;
    }

    /**
     * 重新从磁盘加载设备指纹，用于运行期手工调整 {@code device.json} 后免重启生效。
     */
    public synchronized void reload() {
        profile = load();
    }

    /**
     * 读取设备指纹文件；文件不存在或内容损坏时回退为默认值，并立即回写规范化结果。
     *
     * @return 规范化后的设备指纹
     */
    private Profile load() {
        File file = new File(datapath, FILE_NAME);
        try {
            FileUtils.testFile(file);
        } catch (IOException e) {
            log.warn("创建设备指纹文件失败: {}", file.getAbsolutePath(), e);
        }
        Profile p = null;
        String str = null;
        try {
            str = FileUtils.getStringFromFile(file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("读取设备指纹文件失败: {}", file.getAbsolutePath(), e);
        }
        if (str != null && !str.isBlank()) {
            try {
                p = JSON.parseObject(str, Profile.class);
            } catch (Exception e) {
                log.warn("设备指纹文件格式错误, 使用默认值: {}", e.getMessage());
            }
        }
        if (p == null) p = new Profile();
        // 补齐缺失字段后回写, 保证下一次启动拿到的是同一份指纹
        p.normalize();
        try {
            FileUtils.putStringInFile(JSON.toJSONString(p, JSONWriter.Feature.PrettyFormat), file);
        } catch (Exception e) {
            log.warn("回写设备指纹文件失败: {}", file.getAbsolutePath(), e);
        }
        log.info("已加载设备指纹: openid={} logUid={} version={}({}) soc={}",
                p.getOpenid(), p.getLogUid(), p.getClientVersionName(), p.getClientVersionCode(), p.getCpuHardware());
        return p;
    }

    /**
     * 生成 32 位大写十六进制串，格式与营地 {@code openid} 一致。
     *
     * @return openid 形态的随机串
     */
    private static String randomOpenId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return toHex(bytes);
    }

    /**
     * 字节数组转大写十六进制字符串。
     *
     * @param bytes 源字节
     * @return 十六进制串
     */
    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            chars[i * 2] = HEX[b >>> 4];
            chars[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(chars);
    }

    /**
     * 设备指纹数据结构，字段名与 {@code data/device.json} 一一对应。
     */
    @Data
    public static class Profile {

        /** 账号 openid，32 位大写十六进制；对应抓包头部 {@code openid}。建议填写该 token 对应的真实 openid */
        private String openid;

        /**
         * 游戏侧 openid，32 位大写十六进制；对应抓包头部 {@code gameopenid}。
         *
         * <p>与 {@code openid} <b>不是同一个值</b>：它绑定「账号 + 已选角色」，
         * 仅在选定角色后的请求中出现（{@code /sso/isaccesstokenexpired} 等），
         * 未选角色的 {@code /game/rolelist} 请求不携带。留空时不下发该头部。</p>
         */
        private String gameOpenId;

        /** 设备级日志追踪 id，UUID 大写；对应抓包头部 {@code x-log-uid}，同一安装周期内固定不变 */
        private String logUid;

        /** SoC 型号；对应抓包头部 {@code cpuhardware}。mt6833 = 联发科天玑 700 */
        private String cpuHardware;

        /** 操作系统标识；对应抓包头部 {@code csystem} */
        private String system;

        /** Android 版本名；对应抓包头部 {@code csystemversionname} */
        private String systemVersionName;

        /** Android API Level；对应抓包头部 {@code csystemversioncode} */
        private String systemVersionCode;

        /** 营地客户端版本名；对应抓包头部 {@code cclientversionname} */
        private String clientVersionName;

        /** 营地客户端版本号；对应抓包头部 {@code cclientversioncode} */
        private String clientVersionCode;

        /** 热更新补丁号；对应抓包头部 {@code tinkerid}，留空则由版本号推导为 {@code <code>_64_0} */
        private String tinkerId;

        /** 分发渠道号；对应抓包头部 {@code cchannelid} */
        private String channelId;

        /** 是否 64 位，同时用于 {@code cisarm64} 与 {@code csupportarm64} */
        private boolean arm64;

        /** 请求体压缩开关；对应抓包头部 {@code cgzip} */
        private int gzip;

        /** 游戏 id，王者荣耀固定 20001；同时用于 {@code gameid}/{@code cgameid}/{@code ccurrentgameid} */
        private String gameId;

        /** 大区 id；对应抓包头部 {@code gameareaid}。已选角色时为 1，未选角色时为 0 */
        private String gameAreaId;

        /** 角色 id；对应抓包头部 {@code gameroleid}。已选角色时为实际角色 id，未选角色时为 0 */
        private String gameRoleId;

        /** 服务器 id；对应抓包头部 {@code gameserverid}。已选角色时为实际区服 id，未选角色时为 0 */
        private String gameServerId;

        /** 游戏内性别；对应抓包头部 {@code gameusersex}。已选角色时为角色性别，未选角色时为 0 */
        private String gameUserSex;

        /** 营地资料性别；对应抓包头部 {@code kohdimgender}。1=男 2=女 */
        private int dimGender;

        /**
         * 风控参数；对应抓包头部 {@code encodeparam}。
         * 由客户端安全 SDK 按请求体实时生成，第三方无法离线复算；
         * <b>留空时不会发送该头部</b>——发送错误值比不发送更容易被判定为异常。
         */
        private String encodeParam;

        /**
         * 是否携带 {@code istrpcrequest} 头部。
         *
         * <p>两份真机抓包（{@code /game/rolelist}、{@code /sso/isaccesstokenexpired}）均<b>不含</b>该头部，
         * 故默认关闭。历史版本曾恒为 true，如需兼容老网关可在 {@code device.json} 中置 true。</p>
         */
        private boolean trpc;

        /**
         * 补齐为空的字段，保证指纹自洽且每个请求取值稳定。
         *
         * <p>只处理「缺失」情况，不覆盖调用方写入的合法值。</p>
         */
        public void normalize() {
            if (isBlank(openid)) openid = randomOpenId();
            if (gameOpenId == null) gameOpenId = "";
            if (isBlank(logUid)) logUid = UUID.randomUUID().toString().toUpperCase();
            if (isBlank(cpuHardware)) cpuHardware = "mt6833";
            if (isBlank(system)) system = "android";
            if (isBlank(systemVersionName)) systemVersionName = "12";
            if (isBlank(systemVersionCode)) systemVersionCode = "31";
            if (isBlank(clientVersionName)) clientVersionName = "10.114.1028";
            if (isBlank(clientVersionCode)) clientVersionCode = "2057979709";
            if (isBlank(channelId)) channelId = "10003391";
            if (isBlank(gameId)) gameId = "20001";
            if (isBlank(gameAreaId)) gameAreaId = "1";
            if (isBlank(gameRoleId)) gameRoleId = "0";
            if (isBlank(gameServerId)) gameServerId = "0";
            if (isBlank(gameUserSex)) gameUserSex = "1";
            if (encodeParam == null) encodeParam = "";
        }

        /**
         * 解析实际发送的 tinkerId。
         *
         * <p>真机 tinkerId 恒为 {@code <客户端版本号>_64_0}，这里按版本号推导，
         * 避免手工维护时与 {@code clientVersionCode} 脱节。</p>
         *
         * @return tinkerId
         */
        public String resolveTinkerId() {
            if (!isBlank(tinkerId)) return tinkerId;
            return clientVersionCode + "_64_0";
        }

        /**
         * 字符串是否为空白。
         *
         * @param s 待判断字符串
         * @return 为 null 或仅含空白字符时返回 true
         */
        private static boolean isBlank(String s) {
            return s == null || s.trim().isEmpty();
        }
    }
}
