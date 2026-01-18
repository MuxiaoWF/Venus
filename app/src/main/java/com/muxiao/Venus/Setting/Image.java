package com.muxiao.Venus.Setting;

import static com.muxiao.Venus.common.tools.sendGetRequest;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.muxiao.Venus.common.Constants;
import com.muxiao.Venus.common.HeaderManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Image {
    private final Context context;
    private final HeaderManager header_manager;
    private final ExecutorService executorService;

    // 定义回调接口用于传递数据
    public interface ImageDataCallback {
        void onImageLoaded(List<Map<String, Object>> imageData);

        void onError(Exception e);
    }

    Image(Context context) {
        this.context = context;
        this.header_manager = new HeaderManager(context);
        this.executorService = Executors.newFixedThreadPool(3);
    }

    public void getImageAsync(ImageDataCallback callback, String params) {
        executorService.execute(() -> {
            try {
                Map<String, String> headers = header_manager.get_images_headers();
                String jsonResponse = sendGetRequest(Constants.Urls.BBS_IMAGE_URL + params + "&last_id=&page_size=60", headers, null);
                List<Map<String, Object>> imageData = parseImageData(jsonResponse);
                // 在主线程中回调
                ((android.app.Activity) context).runOnUiThread(() -> callback.onImageLoaded(imageData));
            } catch (Exception e) {
                // 在主线程中回调错误
                ((android.app.Activity) context).runOnUiThread(() -> callback.onError(e));
            }
        });
    }

    /**
     * 解析图片数据
     */
    public List<Map<String, Object>> parseImageData(String jsonResponse) {
        List<Map<String, Object>> imagePosts = new ArrayList<>();
        JsonObject jsonObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
        if (jsonObject.has("data") && !jsonObject.get("data").isJsonNull()) {
            JsonObject data = jsonObject.getAsJsonObject("data");
            if (data.has("list") && !data.get("list").isJsonNull()) {
                JsonArray list = data.getAsJsonArray("list");
                for (JsonElement element : list) {
                    JsonObject itemObject = element.getAsJsonObject();
                    // 获取post对象
                    JsonObject postObject = itemObject.has("post") ? itemObject.getAsJsonObject("post") : new JsonObject();
                    // 获取user对象
                    JsonObject userObject = itemObject.has("user") ? itemObject.getAsJsonObject("user") : new JsonObject();
                    // 创建用于存储帖子信息的Map
                    Map<String, Object> postData = new HashMap<>();

                    // 提取标题信息
                    String title = "";
                    if (postObject.has("subject") && !postObject.get("subject").isJsonNull())
                        title = postObject.get("subject").getAsString();
                    // 提取作者信息
                    String author = "未知作者";
                    if (userObject.has("nickname") && !userObject.get("nickname").isJsonNull())
                        author = userObject.get("nickname").getAsString();
                    // 提取基础信息
                    String cover = "";
                    if (postObject.has("cover") && !postObject.get("cover").isJsonNull())
                        cover = postObject.get("cover").getAsString();
                    long createdAt = 0;
                    if (postObject.has("created_at") && !postObject.get("created_at").isJsonNull())
                        createdAt = postObject.get("created_at").getAsLong();
                    // 提取描述信息
                    String description = "";
                    if (postObject.has("content") && !postObject.get("content").isJsonNull()) {
                        String contentStr = postObject.get("content").getAsString();
                        try {
                            JsonObject content = JsonParser.parseString(contentStr).getAsJsonObject();
                            if (content.has("describe") && !content.get("describe").isJsonNull()) {
                                description = content.get("describe").getAsString();
                                // 去除换行符
                                description = description.replace("\n", "").replace("\r", "");
                                // 如果describe中包含"作品描述"字段，则切分取后段
                                if (description.contains("作品描述：")) {
                                    String[] parts = description.split("作品描述：");
                                    description = parts.length > 1 ? parts[1].trim() : "";
                                } else if (description.contains("作品描述:")) {
                                    String[] parts = description.split("作品描述:");
                                    description = parts.length > 1 ? parts[1].trim() : "";
                                } else if (description.contains("作品描述")) {
                                    String[] parts = description.split("作品描述");
                                    description = parts.length > 1 ? parts[1].trim() : "";
                                } else {
                                    description = "";
                                }
                            }
                        } catch (Exception e) {
                            // 如果content不是JSON对象，则直接使用整个内容作为描述
                            description = contentStr;
                        }
                    }

                    // 提取图片URL列表
                    List<String> imageUrls = new ArrayList<>();
                    // 尝试从images字段获取
                    if (postObject.has("images") && !postObject.get("images").isJsonNull()) {
                        JsonArray postImages = postObject.getAsJsonArray("images");
                        for (JsonElement imgElement : postImages)
                            imageUrls.add(imgElement.getAsString());
                    }
                    // 如果没有图片，尝试从image_list字段获取
                    if (imageUrls.isEmpty() && itemObject.has("image_list") && !itemObject.get("image_list").isJsonNull()) {
                        JsonArray imageList = itemObject.getAsJsonArray("image_list");
                        for (JsonElement imageElement : imageList) {
                            JsonObject imageObj = imageElement.getAsJsonObject();
                            if (imageObj.has("url") && !imageObj.get("url").isJsonNull())
                                imageUrls.add(imageObj.get("url").getAsString());
                        }
                    }
                    // 如果仍然没有图片，使用cover作为唯一图片
                    if (imageUrls.isEmpty() && !cover.isEmpty())
                        imageUrls.add(cover);
                    // 将数据放入Map中
                    postData.put("title", title);
                    postData.put("description", description);
                    postData.put("author", author);
                    postData.put("timestamp", createdAt);
                    postData.put("images", imageUrls);
                    postData.put("cover", cover);
                    imagePosts.add(postData);
                }
            }
        }
        return imagePosts;
    }

    // 关闭线程池，释放资源
    public void destroy() {
        if (executorService != null && !executorService.isShutdown())
            executorService.shutdown();
    }
}
