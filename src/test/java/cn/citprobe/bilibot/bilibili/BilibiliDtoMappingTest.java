package cn.citprobe.bilibot.bilibili;

import cn.citprobe.bilibot.bilibili.model.BilibiliDtos;
import cn.citprobe.bilibot.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliDtoMappingTest {

    @Test
    void mapsBooleanFieldsWithSnakeCase() throws Exception {
        BilibiliDtos.NavResponse nav = Json.BILIBILI.readValue(
                "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":42,\"uname\":\"机器人\"}}",
                BilibiliDtos.NavResponse.class
        );

        assertTrue(nav.data().isLogin());

        BilibiliDtos.NavResponse legacy = Json.BILIBILI.readValue(
                "{\"code\":0,\"data\":{\"is_login\":true,\"mid\":42}}",
                BilibiliDtos.NavResponse.class
        );
        assertTrue(legacy.data().isLogin());
    }

    @Test
    void mapsCursorAndItemBooleans() throws Exception {
        BilibiliDtos.MsgFeedResponse feed = Json.BILIBILI.readValue("""
                {
                  "code": 0,
                  "data": {
                    "cursor": {"is_end": false, "id": 1, "time": 2},
                    "items": [{
                      "id": 3,
                      "user": {"mid": 7},
                      "item": {
                        "subject_id": 8,
                        "source_id": 9,
                        "hide_reply_button": true
                      },
                      "is_multi": 1
                    }]
                  }
                }
                """, BilibiliDtos.MsgFeedResponse.class);

        assertTrue(!feed.data().cursor().isEnd());
        assertTrue(feed.data().items().get(0).item().hideReplyButton());
        assertTrue(feed.data().items().get(0).isMulti() == 1);
    }
}
