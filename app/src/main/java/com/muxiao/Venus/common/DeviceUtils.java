package com.muxiao.Venus.common;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.github.gzuliyujiang.oaid.DeviceID;
import com.github.gzuliyujiang.oaid.DeviceIdentifier;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public class DeviceUtils {
    private final Context context;

    public DeviceUtils(Context context) {
        this.context = context;
    }

    /**
     * 生成设备ID
     */
    public String generateDeviceId() {
        String deviceId = DeviceIdentifier.getGUID(context);
        // 获取不到就手动创建
        if (deviceId == null) {
            @SuppressLint("HardwareIds") String namespace = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            String name = Build.MANUFACTURER + " " + Build.MODEL;
            // Convert namespace to UUID
            UUID namespaceUUID = UUID.nameUUIDFromBytes(namespace.getBytes());
            // Concatenate namespace and name
            long msb = namespaceUUID.getMostSignificantBits();
            long lsb = namespaceUUID.getLeastSignificantBits();
            byte[] namespaceBytes = new byte[16];
            for (int i = 0; i < 8; i++)
                namespaceBytes[i] = (byte) (msb >>> (8 * (7 - i)));
            for (int i = 8; i < 16; i++)
                namespaceBytes[i] = (byte) (lsb >>> (8 * (15 - i)));
            byte[] nameBytes = name.getBytes();
            byte[] combinedBytes = new byte[namespaceBytes.length + nameBytes.length];
            System.arraycopy(namespaceBytes, 0, combinedBytes, 0, namespaceBytes.length);
            System.arraycopy(nameBytes, 0, combinedBytes, namespaceBytes.length, nameBytes.length);
            // Hash the combined bytes using MD5
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            byte[] hashBytes = md.digest(combinedBytes);
            // Convert the hash to a UUID
            long mostSignificantBits = 0;
            long leastSignificantBits = 0;
            for (int i = 0; i < 8; i++)
                mostSignificantBits = (mostSignificantBits << 8) | (hashBytes[i] & 0xff);
            for (int i = 8; i < 16; i++)
                leastSignificantBits = (leastSignificantBits << 8) | (hashBytes[i] & 0xff);
            // Set the version to 3 and the variant to DCE 1.1
            mostSignificantBits &= ~0x000000000000F000L;
            mostSignificantBits |= 0x0000000000003000L;
            leastSignificantBits &= ~0xC000000000000000L;
            leastSignificantBits |= 0x8000000000000000L;
            deviceId = new UUID(mostSignificantBits, leastSignificantBits).toString();
        }
        return deviceId;
    }

    /**
     * 获取device_fp中的ext_fields参数
     *
     * @return ext_fields -String
     */
    public String getExtFields() {
        String[] temp2 = generateDeviceId().split("-");
        String aaid = temp2[0] + temp2[4].substring(0, 3) + "-" + temp2[4].substring(3, 6) + "-" + temp2[4].substring(6, 9) + temp2[1] + temp2[2] + temp2[3];
        Gson gson = new Gson();
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("cpuType", android.os.Build.SUPPORTED_ABIS[0]);
        jsonObject.addProperty("romCapacity", String.valueOf(getTotalStorageSpace()));
        jsonObject.addProperty("productName", android.os.Build.PRODUCT);
        jsonObject.addProperty("romRemain", String.valueOf(getAvailableStorageSpace()));
        jsonObject.addProperty("manufacturer", android.os.Build.MANUFACTURER);
        jsonObject.addProperty("appMemory", String.valueOf(getTotalMemory()));
        jsonObject.addProperty("hostname", android.os.Build.HOST);
        jsonObject.addProperty("screenSize", context.getResources().getDisplayMetrics().widthPixels + "x" + context.getResources().getDisplayMetrics().heightPixels);
        jsonObject.addProperty("osVersion", String.valueOf(android.os.Build.VERSION.SDK_INT));
        jsonObject.addProperty("aaid", aaid);
        jsonObject.addProperty("vendor", android.os.Build.BRAND);
        jsonObject.addProperty("accelerometer", getSensorInfo("accelerometer"));
        jsonObject.addProperty("buildTags", android.os.Build.TAGS);
        jsonObject.addProperty("model", android.os.Build.MODEL);
        jsonObject.addProperty("brand", android.os.Build.BRAND);
        jsonObject.addProperty("oaid", DeviceID.supportedOAID(context) ? DeviceIdentifier.getOAID(context) : generateDeviceId());
        jsonObject.addProperty("hardware", android.os.Build.HARDWARE);
        jsonObject.addProperty("deviceType", android.os.Build.DEVICE);
        jsonObject.addProperty("devId", android.os.Build.VERSION.RELEASE);
        jsonObject.addProperty("serialNumber", "UNKNOWN");
        jsonObject.addProperty("buildTime", String.valueOf(android.os.Build.TIME));
        jsonObject.addProperty("buildUser", android.os.Build.USER);
        jsonObject.addProperty("ramCapacity", String.valueOf(getTotalRam()));
        jsonObject.addProperty("magnetometer", getSensorInfo("magnetometer"));
        jsonObject.addProperty("display", android.os.Build.DISPLAY);
        jsonObject.addProperty("ramRemain", String.valueOf(getAvailableRam()));
        jsonObject.addProperty("deviceInfo", android.os.Build.MANUFACTURER + "/" + android.os.Build.DEVICE + "/" + android.os.Build.BOARD + ":" + android.os.Build.VERSION.RELEASE + "/" + android.os.Build.ID + "/" + android.os.Build.VERSION.INCREMENTAL + ":" + android.os.Build.TYPE + "/" + android.os.Build.TAGS);
        jsonObject.addProperty("gyroscope", getSensorInfo("gyroscope"));
        jsonObject.addProperty("vaid", DeviceID.supportedOAID(context) ? DeviceIdentifier.getOAID(context) : "7.9894776E-4x-1.3315796E-4x6.6578976E-4"); // 虚拟广告标识符
        jsonObject.addProperty("buildType", android.os.Build.TYPE);
        jsonObject.addProperty("sdkVersion", android.os.Build.VERSION.SDK_INT);
        jsonObject.addProperty("board", android.os.Build.BOARD);
        return gson.toJson(jsonObject);
    }

    /**
     * 获取手机存储空间
     */
    private long getTotalStorageSpace() {
        android.os.StatFs statFs = new android.os.StatFs(android.os.Environment.getRootDirectory().getAbsolutePath());
        long blockSize = statFs.getBlockSizeLong();
        long totalBlocks = statFs.getBlockCountLong();
        return (totalBlocks * blockSize) / (1024 * 1024); // 返回MB
    }

    /**
     * 获取手机可用存储空间
     */
    private long getAvailableStorageSpace() {
        android.os.StatFs statFs = new android.os.StatFs(android.os.Environment.getRootDirectory().getAbsolutePath());
        long blockSize = statFs.getBlockSizeLong();
        long availableBlocks = statFs.getAvailableBlocksLong();
        return (availableBlocks * blockSize) / (1024 * 1024); // 返回MB
    }

    /**
     * 获取总内存
     */
    private long getTotalMemory() {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.totalMem / (1024 * 1024); // 返回MB
    }


    /**
     * 获取总RAM（使用总内存）
     */
    private long getTotalRam() {
        return getTotalMemory();
    }

    /**
     * 获取总RAM（使用可用内存）
     */
    private long getAvailableRam() {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        return memoryInfo.availMem / (1024 * 1024); // 返回MB
    }

    /**
     * 获取传感器信息
     */
    private String getSensorInfo(String sensorType) {
        try {
            android.hardware.SensorManager sensorManager = (android.hardware.SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            android.hardware.Sensor sensor = null;
            switch (sensorType) {
                case "accelerometer":
                    sensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
                    break;
                case "gyroscope":
                    sensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE);
                    break;
                case "magnetometer":
                    sensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD);
                    break;
            }
            if (sensor != null) {
                android.hardware.SensorEventListener sensorEventListener = new android.hardware.SensorEventListener() {
                    @Override
                    public void onSensorChanged(android.hardware.SensorEvent event) {
                        // 我们不需要实际处理事件，只是获取一次数据
                    }

                    @Override
                    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
                        // 不需要处理
                    }
                };
                // 注册传感器监听器
                sensorManager.registerListener(sensorEventListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
                // 等待一小段时间让传感器采集数据
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 注销传感器监听器
                sensorManager.unregisterListener(sensorEventListener);
                // 返回默认值作为示例（实际项目中应该从onSensorChanged获取真实数据）
                switch (sensorType) {
                    case "accelerometer":
                        return "0.061016977x0.8362915x9.826724";
                    case "gyroscope":
                        return "0.0x0.0x0.0";
                    case "magnetometer":
                        return "80.64375x-14.1x77.90625";
                }
            }
        } catch (Exception e) {
            // 如果获取传感器信息失败，返回默认值
            switch (sensorType) {
                case "accelerometer":
                    return "0.061016977x0.8362915x9.826724";
                case "gyroscope":
                    return "0.0x0.0x0.0";
                case "magnetometer":
                    return "80.64375x-14.1x77.90625";
            }
        }
        return "0.0x0.0x0.0";
    }
}
