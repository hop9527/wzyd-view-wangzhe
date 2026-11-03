package io.github.gdpl2112;

import io.github.gdpl2112.config.BindConfig;
import io.github.gdpl2112.config.CampDevice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 营地接口地址与请求头构造。
 *
 * <p>请求头字段与取值严格对齐真机抓包
 * （{@code docs/[91] request_kohcamp.qq.com_message.txt}），各字段含义见
 * {@code docs/request-fields.md}。</p>
 *
 * <p>实现要点：</p>
 * <ol>
 *     <li>头部名统一使用抓包中的<b>全小写</b>形式。HTTP/2 下头部名本身即小写，
 *     混用大小写会让同一字段出现重复项（旧实现即存在
 *     {@code cClientVersionCode} 与 {@code cclientversioncode} 同时下发的问题）；</li>
 *     <li>与设备相关的静态字段统一取自 {@link CampDevice}，保证跨请求稳定；</li>
 *     <li>仅 {@code crand}、{@code traceparent} 按请求生成，与真机行为一致；</li>
 *     <li>{@code content-type} 按请求体类型区分，proto 接口发送
 *     {@code application/proto; charset=UTF-8}，而非 JSON 类型；</li>
 *     <li>不再下发 {@code host}，交由 HTTP 客户端按目标 URL 生成，避免跨域请求错配虚拟主机。</li>
 * </ol>
 *
 * @author github kloping
 * @date 2025/6/30-22:17
 */
@Slf4j
@Component
public class HttpApi {

    public static final String MAIN_URL = "https://kohcamp.qq.com";
    public static final String GAME_URL = "https://ssl.kohsocialapp.qq.com:10001";

    public static final String MGAME_URL = MAIN_URL + "/game";
    //营地
    public static final String BATTLE_HISTORY = MGAME_URL + "/morebattlelist";
    public static final String BATTLE_DETAIL = MGAME_URL + "/battledetail";
    public static final String SEASON_STATUS = MGAME_URL + "/seasonpage";
    public static final String PROFILE_INDEX = MGAME_URL + "/profile/index";
    public static final String PROFILE_HERO_LIST = MGAME_URL + "/profile/herolist";
    public static final String HERO_LIST = "https://pvp.qq.com/web201605/js/herolist.json";
    //基本
    public static final String USER_PROFILE = MAIN_URL + "/userprofile/profile";
    public static final String SEARCH_BY_TYPE = MAIN_URL + "/search/getbytype";
    //特殊
    public static final String ALL_ROLE_LIST_V3 = GAME_URL + "/game/allrolelistv3";
    public static final String SKIN_LIST = GAME_URL + "/play/h5getheroskinlist";

    //资料信息
    public static final String HERO_RANK_LIST = MAIN_URL + "/hero/getmaintranklist";
    public static final String PAGE_HERO_HISTORY = MAIN_URL + "/gametoolbox/hero/record/pagedetails";
    public static final String HERO_HISTORY = MAIN_URL + "/gametoolbox/hero/record/historydetails";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** 旧版客户端版本名，与 {@link #LEGACY_CLIENT_VERSION_CODE} 配套 */
    private static final String LEGACY_CLIENT_VERSION_NAME = "8.92.0125";

    /** 旧版客户端版本号，与 {@link #LEGACY_CLIENT_VERSION_NAME} 配套 */
    private static final String LEGACY_CLIENT_VERSION_CODE = "2037857908";

    @Autowired
    public BindConfig bindConfig;

    @Autowired
    public CampDevice campDevice;

    /**
     * 请求体类型，决定 {@code content-type} 头部取值。
     */
    public enum Payload {
        /** 普通 JSON 请求体 */
        JSON("application/json; charset=UTF-8"),
        /** protobuf 请求体，营地新版接口使用 */
        PROTO("application/proto; charset=UTF-8"),
        /** 表单请求体，旧版 kohsocialapp 接口使用 */
        FORM("application/x-www-form-urlencoded");

        private final String contentType;

        Payload(String contentType) {
            this.contentType = contentType;
        }

        /**
         * @return 对应的 content-type 头部值
         */
        public String contentType() {
            return contentType;
        }
    }

    /**
     * 构造请求头（默认 JSON 请求体）。
     *
     * @return 可直接交给 HTTP 客户端的头部映射
     */
    public Map<String, String> getRequestHeaders() {
        return getHeaders(Payload.JSON);
    }

    /**
     * 构造 JSON 请求体请求头。
     *
     * @return 头部映射
     */
    public Map<String, String> getJsonHeaders() {
        return getHeaders(Payload.JSON);
    }

    /**
     * 构造 protobuf 请求体请求头。
     *
     * @return 头部映射
     */
    public Map<String, String> getProtoHeaders() {
        return getHeaders(Payload.PROTO);
    }

    /**
     * 构造表单请求体请求头，用于 {@code ssl.kohsocialapp.qq.com} 旧版服务。
     *
     * @return 头部映射
     */
    public Map<String, String> getFormHeaders() {
        return getHeaders(Payload.FORM);
    }

    /**
     * 构造指定请求体类型的请求头。
     *
     * @param payload 请求体类型
     * @return 头部映射
     */
    public Map<String, String> getHeaders(Payload payload) {
        CampDevice.Profile d = campDevice.getProfile();
        BindConfig.UserToken token = bindConfig.getToken();
        String userId = token == null ? null : token.user;
        String userToken = token == null ? null : token.token;

        Map<String, String> map = new LinkedHashMap<>();

        // ---- 传输与编码：与 okhttp/4.9.1 真机行为一致 ----
        map.put("content-type", payload.contentType());
        map.put("user-agent", "okhttp/4.9.1");
        map.put("accept-encoding", "gzip");
        // 空值也必须下发: 真机以空串显式声明「本次不加密 / 不接受加密响应」
        map.put("content-encrypt", "");
        map.put("accept-encrypt", "");
        map.put("noencrypt", "1");
        map.put("x-client-proto", "https");

        // ---- 客户端版本：四者必须指向同一版本 ----
        map.put("cclientversionname", d.getClientVersionName());
        map.put("cclientversioncode", d.getClientVersionCode());
        map.put("tinkerid", d.resolveTinkerId());
        map.put("cchannelid", d.getChannelId());

        // ---- 系统与硬件 ----
        map.put("csystem", d.getSystem());
        map.put("csystemversionname", d.getSystemVersionName());
        map.put("csystemversioncode", d.getSystemVersionCode());
        map.put("cisarm64", String.valueOf(d.isArm64()));
        map.put("csupportarm64", String.valueOf(d.isArm64()));
        map.put("cgzip", String.valueOf(d.getGzip()));
        map.put("cpuhardware", d.getCpuHardware());

        // ---- 游戏上下文：未选定角色时统一为 0，与真机 rolelist 请求一致 ----
        map.put("gameid", d.getGameId());
        map.put("cgameid", d.getGameId());
        map.put("ccurrentgameid", d.getGameId());
        map.put("gameareaid", d.getGameAreaId());
        map.put("gameroleid", d.getGameRoleId());
        map.put("gameserverid", d.getGameServerId());
        map.put("gameusersex", d.getGameUserSex());

        // ---- 账号态 ----
        map.put("token", nullToEmpty(userToken));
        map.put("userid", nullToEmpty(userId));
        map.put("openid", d.getOpenid());
        map.put("kohdimgender", String.valueOf(d.getDimGender()));
        // gameopenid 与 openid 不是同一个值: 前者绑定「账号+已选角色」, 选定角色后才出现
        if (d.getGameOpenId() != null && !d.getGameOpenId().trim().isEmpty()) {
            map.put("gameopenid", d.getGameOpenId().trim());
        }

        // ---- 每请求变化项 ----
        map.put("crand", String.valueOf(System.currentTimeMillis()));
        map.put("x-log-uid", d.getLogUid());
        map.put("traceparent", newTraceParent());

        if (d.isTrpc()) {
            map.put("istrpcrequest", "true");
        }
        // 安全 SDK 实时生成的参数, 无值时不发送, 避免下发错误签名
        if (d.getEncodeParam() != null && !d.getEncodeParam().trim().isEmpty()) {
            map.put("encodeparam", d.getEncodeParam().trim());
        }
        return map;
    }

    /**
     * 构造旧版客户端请求头，用于仍在按老版本号校验的接口
     * （{@code /userprofile/profile}）。
     *
     * <p>该变体<b>不下发</b> {@code x-log-uid} 与 {@code traceparent}：
     * 这两个头部由新版 SDK 引入，老版本号客户端不会携带，
     * 混用会造成版本特征自相矛盾。</p>
     *
     * @return 头部映射
     */
    public Map<String, String> getRequestHeadersOld() {
        Map<String, String> map = getHeaders(Payload.JSON);
        map.put("cclientversionname", LEGACY_CLIENT_VERSION_NAME);
        map.put("cclientversioncode", LEGACY_CLIENT_VERSION_CODE);
        map.put("tinkerid", LEGACY_CLIENT_VERSION_CODE + "_64_0");
        map.remove("x-log-uid");
        map.remove("traceparent");
        return map;
    }

    /**
     * 生成 W3C Trace Context 头，格式 {@code 00-<32hex traceId>-<16hex spanId>-01}。
     *
     * <p>营地新版客户端使用带链路追踪的 okhttp，真机请求均携带该头部。
     * 每个请求生成一次，共 24 字节随机数。</p>
     *
     * @return traceparent 头部值
     */
    private static String newTraceParent() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(55);
        sb.append("00-");
        appendHex(sb, bytes, 0, 16);
        sb.append('-');
        appendHex(sb, bytes, 16, 8);
        sb.append("-01");
        return sb.toString();
    }

    /**
     * 追加十六进制编码。
     *
     * @param sb    目标缓冲
     * @param bytes 源字节
     * @param off   起始下标
     * @param len   字节数
     */
    private static void appendHex(StringBuilder sb, byte[] bytes, int off, int len) {
        for (int i = 0; i < len; i++) {
            int b = bytes[off + i] & 0xFF;
            sb.append(HEX[b >>> 4]).append(HEX[b & 0x0F]);
        }
    }

    /**
     * null 转空串，HTTP 客户端不接受 null 头部值。
     *
     * @param s 源字符串
     * @return 非 null 字符串
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
