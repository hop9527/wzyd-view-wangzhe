package io.github.gdpl2112.controller;

import io.github.gdpl2112.HttpApi;
import io.github.gdpl2112.config.BindConfig;
import io.github.gdpl2112.dto.UserDataProto;
import io.github.gdpl2112.utils.CustomProtobufBuilder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 *
 * create on 20:03
 *
 * @author github kloping
 * @since 2025/10/8
 */
@Slf4j
@RestController
@RequestMapping("/query")
public class UidQueryController {
    @Autowired
    HttpApi api;

    OkHttpClient client = new OkHttpClient();

    public static class UserData {
        public String uid;
        public String name;
        public String dw;
        public String region;
        public String level;
        public String avatar;
    }

    @RequestMapping("/show")
    public Object show(@RequestParam("name") String name) {
        byte[] bytes = CustomProtobufBuilder.buildCustomProtobufMessage(name);

        // 该接口请求体为 protobuf, content-type 必须与抓包一致, 不能沿用 JSON 类型
        RequestBody body = RequestBody.create(bytes, MediaType.parse(HttpApi.Payload.PROTO.contentType()));

        Map<String, String> headers = api.getProtoHeaders();
        Request request = new Request.Builder()
                .url(HttpApi.SEARCH_BY_TYPE)
                .post(body).headers(Headers.of(headers)).build();

        List<Object> result = new LinkedList<>();

        try (Response response = client.newCall(request).execute()) {
            byte[] data = response.body().bytes();
            UserDataProto.Response allData = UserDataProto.Response.parseFrom(data);
            for (UserDataProto.UserGroup userGroup : allData.getData().getUserGroupsList()) {
                for (UserDataProto.User user : userGroup.getUsersList()) {
                    UserData userData = new UserData();
                    userData.uid = String.valueOf(user.getDetail().getFriendUid());
                    userData.name = user.getDetail().getNickname();
                    userData.region = user.getDetail().getRegion();
                    userData.level = String.valueOf(user.getDetail().getLevel());
                    userData.avatar = user.getDetail().getAvatarUrl();
                    userData.dw = user.getDetail().getTitle1();
                    result.add(userData);
                }
            }
            if (result.isEmpty()) {
                UserData userData = new UserData();
                userData.uid = "---";
                userData.name = name;
                userData.region = "未找到相关用户";
                userData.level = "";
                userData.avatar = "";
                userData.dw = "";
                result.add(userData);
            }
            return result;
        } catch (Exception e) {
            log.error("show error:{}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
