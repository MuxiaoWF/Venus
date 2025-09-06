非常非常简陋的APP

由于鄙人不会设计，页面也做的比较丑（尽管使用了material3）

功能：

1. 米游社一键每日签到任务（包含米游币和游戏的签到）
2. 抽卡链接获取功能（登录后直接获取原神和绝区零的，可通过云游戏获取原神和星铁的）
    * 应该不会加分析了，直接导入到微信小程序吧

---

原本想加一个微信原神小程序的，结果发现要code，就懒得再搞了，这里mark一下有兴趣的可以看看：

* GET https://hk4e-api.mihoyo.com/event/doorman/v1/event/wechat_shop/wechatmp/exchange?app=event&client_name=wechat_shop&platform=wechatmp&code=
    * 后面的code可能是微信小程序的code？
    * header：
      ![header](pic/1.png)
    * 返回值
```json
{
  "retcode": 0,
  "message": "OK",
  "data": {
    "token": "nuzYa67o"
  }
}
```    

* POST https://hk4e-api.mihoyo.com/event/weixinpointsmall/sign
    * header中需要添加刚刚返回的token -> x-rpc-token:
    * header：
      ![header](pic/2.png)
    * 返回值
```json
{
  "retcode": 0,
  "message": "OK",
  "data": {
    "coin": 5,
    "total_coin": 825,
    "tasks": [
      {
        "task_config_id": 1001,
        "status": "TS_DONE",
        "desc": "3",
        "icon": "https://fastcdn.mihoyo.com/static-resource-v2/2024/06/14/881b6e74975a2989f0a119427871e5be_3617327020012540585.png",
        "icon_detail": "https://fastcdn.mihoyo.com/static-resource-v2/2024/06/14/3eb599a723bfe9ef09e6fc478c8262d0_9119262560095512903.png",
        "award_desc": "摩拉×20000"
      },
      {
        "task_config_id": 1002,
        "status": "TS_DONE",
        "desc": "5",
        "icon": "https://fastcdn.mihoyo.com/static-resource-v2/2024/06/14/ebf1ca1f83d719c1778b8d01fb0e8115_1027578354241986805.png",
        "icon_detail": "https://fastcdn.mihoyo.com/static-resource-v2/2024/06/14/8a979fd2e075e5f60959a4d8f5dfc536_7772729234138631324.png",
        "award_desc": "甜甜花酿鸡×3"
      },
      {
        "task_config_id": 1003,
        "status": "TS_DOING",
        "desc": "7",
        "icon": "https://fastcdn.mihoyo.com/static-resource-v2/2024/06/14/1ae7961356e21dfe3d8f3b66b67ba852_6570677886805205025.png",
        "icon_detail": "https://fastcdn.mihoyo.com/static-resource-v2/2024/06/14/ddcb604c9085c4b7357e278fd76aa573_7905595052265201036.png",
        "award_desc": "冒险家的经验×5"
      },
      {
        "task_config_id": 1004,
        "status": "TS_DOING",
        "desc": "9",
        "icon": "https://fastcdn.mihoyo.com/static-resource-v2/2024/06/14/881b6e74975a2989f0a119427871e5be_3617327020012540585.png",
        "icon_detail": "https://fastcdn.mihoyo.com/static-resource-v2/2024/06/14/e662b4cd949e7ad23967d65962468a0e_7919866827787859077.png",
        "award_desc": "大英雄的经验×3"
      }
    ],
    "sign_days": 5,
    "continue_sign_days": 5
  }
}
```